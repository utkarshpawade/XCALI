#!/usr/bin/env bash
# Builds the three images locally, pushes them to ECR, and rolls the stack over
# on the EC2 host. Run provision.sh first.
#
# Images are built here rather than on the instance because a 1 GB t3.micro
# cannot complete a Next.js production build.
set -euo pipefail

# Resolved once, absolutely: the build step below cds to the repository root,
# and coming back with $(dirname $BASH_SOURCE) is a no-op when the script was
# invoked as ./deploy.sh - dirname is then just '.'. That left relative paths
# such as the ssh key resolving against the repository root instead of here.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"
STATE_FILE=".state.env"
[[ -f "$STATE_FILE" ]] || { echo "No $STATE_FILE. Run ./provision.sh first." >&2; exit 1; }
# shellcheck disable=SC1090
source "$STATE_FILE"

AWS="${AWS_CLI:-aws}"
PROFILE_ARG=""
[[ -n "${AWS_PROFILE:-}" ]] && PROFILE_ARG="--profile ${AWS_PROFILE}"
# The AWS CLI emits CRLF on Windows. A stray \r survives pipes and `read`,
# and AWS rejects it as a control character (RDS) or silently bakes it into
# values like the CloudFront domain and the .pem key, so strip it centrally.
aws_() { $AWS --region "$REGION" $PROFILE_ARG "$@" | tr -d '\r'; }
log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

REPO_ROOT=$(cd ../.. && pwd)
TAG="${IMAGE_TAG:-$(git -C "$REPO_ROOT" rev-parse --short HEAD)}"
SSH_OPTS=(-i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10)

log "Signing in to ECR"
aws_ ecr get-login-password | docker login --username AWS --password-stdin "$ECR_REGISTRY"

# Stacks provisioned before the Spring Boot migration only have the old
# excalidraw-http / excalidraw-ws repositories.
aws_ ecr describe-repositories --repository-names excalidraw-backend >/dev/null 2>&1 || {
  echo "ECR repository excalidraw-backend is missing. Re-run ./provision.sh (it is idempotent)." >&2
  exit 1
}

# The instance is x86_64; Docker Desktop will happily build arm64 on some hosts
# and the resulting image fails at exec time with a format error.
BUILD=(docker build --platform linux/amd64)

log "Building images ($TAG)"
cd "$REPO_ROOT"

# The Spring Boot backend is self-contained, so its build context is its own
# directory rather than the whole workspace.
"${BUILD[@]}" -f apps/backend/Dockerfile \
  -t "$ECR_REGISTRY/excalidraw-backend:$TAG" -t "$ECR_REGISTRY/excalidraw-backend:latest" apps/backend

# NEXT_PUBLIC_* are compiled into the client bundle, so the public URL has to be
# known at build time. This is why CloudFront is created before the first deploy.
"${BUILD[@]}" -f apps/excelidraw-frontend/Dockerfile \
  --build-arg "NEXT_PUBLIC_HTTP_BACKEND=$PUBLIC_ORIGIN/api" \
  --build-arg "NEXT_PUBLIC_WS_URL=wss://$PUBLIC_DOMAIN/ws" \
  -t "$ECR_REGISTRY/excalidraw-frontend:$TAG" -t "$ECR_REGISTRY/excalidraw-frontend:latest" .

log "Pushing to ECR"
for repo in excalidraw-backend excalidraw-frontend; do
  docker push "$ECR_REGISTRY/$repo:$TAG"
  docker push "$ECR_REGISTRY/$repo:latest"
done

cd "$SCRIPT_DIR"

log "Waiting for the instance bootstrap to finish"
BOOTSTRAP_MARKER=/var/lib/cloud/instance/excalidraw-bootstrap-done
for i in {1..40}; do
  if ssh "${SSH_OPTS[@]}" "ec2-user@$PUBLIC_IP" "test -f $BOOTSTRAP_MARKER" 2>/dev/null; then
    break
  fi
  if [[ $i -eq 40 ]]; then
    # The poll above discards stderr, so repeat it once with the error visible;
    # an ssh problem and an unfinished bootstrap look identical otherwise.
    echo "Bootstrap check never succeeded. Retrying once with ssh errors shown:" >&2
    ssh "${SSH_OPTS[@]}" "ec2-user@$PUBLIC_IP" "test -f $BOOTSTRAP_MARKER" >&2 || true
    echo "If ssh itself is fine, check /var/log/excalidraw-bootstrap.log on the box." >&2
    exit 1
  fi
  sleep 15
done

log "Shipping configuration"
scp "${SSH_OPTS[@]}" docker-compose.prod.yml Caddyfile "ec2-user@$PUBLIC_IP:/opt/excalidraw/"

# Written over ssh rather than scp so the secrets never touch a local file that
# is not already gitignored.
ssh "${SSH_OPTS[@]}" "ec2-user@$PUBLIC_IP" "cat > /opt/excalidraw/.env && chmod 600 /opt/excalidraw/.env" <<ENV
ECR_REGISTRY=$ECR_REGISTRY
IMAGE_TAG=$TAG
DATABASE_URL=$DATABASE_URL
JWT_SECRET=$JWT_SECRET
PUBLIC_ORIGIN=$PUBLIC_ORIGIN
PUBLIC_DOMAIN=$PUBLIC_DOMAIN
ENV

log "Rolling out"
ssh "${SSH_OPTS[@]}" "ec2-user@$PUBLIC_IP" bash -s <<REMOTE
set -euo pipefail
cd /opt/excalidraw
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR_REGISTRY
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d --remove-orphans
docker image prune -f
docker compose -f docker-compose.prod.yml ps
REMOTE

log "Verifying"
sleep 10
# Caddy matches on the Host header, so these must go through the domain; the
# raw IP would not match the site block at all.
for path in /_health /api/health /ws/health; do
  printf "  %-12s -> " "$path"
  curl -fsS --max-time 30 "$PUBLIC_ORIGIN$path" || echo "not ready yet"
  echo
done

cat <<DONE

  Deployed $TAG

  App    $PUBLIC_ORIGIN
  Logs   ssh -i deploy/aws/$KEY_FILE ec2-user@$PUBLIC_IP 'cd /opt/excalidraw && docker compose -f docker-compose.prod.yml logs -f'

  Caddy requests a Let's Encrypt certificate the first time it starts, which
  takes a few seconds. Until that finishes the checks above can fail while the
  containers are already healthy - 'docker compose logs caddy' shows progress.

DONE
