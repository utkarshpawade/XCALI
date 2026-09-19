#!/usr/bin/env bash
# Deletes everything provision.sh created. Free tier lapses, and an Elastic IP
# that is not attached to a running instance is billed hourly, so tearing the
# stack down properly matters more than it looks.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
STATE_FILE=".state.env"
[[ -f "$STATE_FILE" ]] || { echo "No $STATE_FILE; nothing recorded to delete." >&2; exit 1; }
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

PREFIX="${STACK_PREFIX:-excalidraw}"

cat <<WARNING

This deletes, in account $ACCOUNT_ID / $REGION:

  EC2 instance      ${INSTANCE_ID:-none}
  Elastic IP        ${PUBLIC_IP:-none}
  RDS database      ${DB_HOST:-none}   *** all drawings are lost ***
  CloudFront        ${CF_ID:-none}
  ECR repositories  excalidraw-backend, excalidraw-frontend
                    (and excalidraw-http, excalidraw-ws from before the Spring Boot move)
  Security groups, key pair, IAM role

WARNING
read -rp "Type 'destroy' to continue: " confirm
[[ "$confirm" == "destroy" ]] || { echo "Aborted."; exit 1; }

if [[ -n "${CF_ID:-}" ]]; then
  log "Disabling CloudFront (deletion needs it disabled and fully propagated)"
  ETAG=$(aws_ cloudfront get-distribution-config --id "$CF_ID" --query ETag --output text)
  aws_ cloudfront get-distribution-config --id "$CF_ID" \
    --query DistributionConfig >cf-disable.json
  # Flip Enabled to false without disturbing the rest of the config.
  python -c "
import json
c = json.load(open('cf-disable.json'))
c['Enabled'] = False
json.dump(c, open('cf-disable.json', 'w'))
"
  aws_ cloudfront update-distribution --id "$CF_ID" \
    --distribution-config file://cf-disable.json --if-match "$ETAG" >/dev/null
  rm -f cf-disable.json
  echo "Waiting for the distribution to finish disabling (this is slow, ~10 min)"
  aws_ cloudfront wait distribution-deployed --id "$CF_ID"
  ETAG=$(aws_ cloudfront get-distribution-config --id "$CF_ID" --query ETag --output text)
  aws_ cloudfront delete-distribution --id "$CF_ID" --if-match "$ETAG" || \
    echo "Delete it manually once propagation completes: $CF_ID"
fi

if [[ -n "${INSTANCE_ID:-}" ]]; then
  log "Terminating $INSTANCE_ID"
  aws_ ec2 terminate-instances --instance-ids "$INSTANCE_ID" >/dev/null
  aws_ ec2 wait instance-terminated --instance-ids "$INSTANCE_ID"
fi

if [[ -n "${ALLOC_ID:-}" ]]; then
  log "Releasing the Elastic IP"
  aws_ ec2 release-address --allocation-id "$ALLOC_ID" || true
fi

log "Deleting the database"
aws_ rds delete-db-instance --db-instance-identifier "$PREFIX-db" \
  --skip-final-snapshot --delete-automated-backups >/dev/null || true
aws_ rds wait db-instance-deleted --db-instance-identifier "$PREFIX-db" || true
aws_ rds delete-db-subnet-group --db-subnet-group-name "$PREFIX-db-subnets" || true

log "Deleting ECR repositories"
for repo in excalidraw-backend excalidraw-frontend excalidraw-http excalidraw-ws; do
  aws_ ecr delete-repository --repository-name "$repo" --force >/dev/null 2>&1 || true
done

log "Deleting security groups"
aws_ ec2 delete-security-group --group-id "${RDS_SG:-}" 2>/dev/null || true
aws_ ec2 delete-security-group --group-id "${EC2_SG:-}" 2>/dev/null || true

log "Deleting the key pair and IAM role"
aws_ ec2 delete-key-pair --key-name "$PREFIX-key" || true
rm -f "$PREFIX-key.pem"
aws_ iam remove-role-from-instance-profile --instance-profile-name "$PREFIX-ec2-role" \
  --role-name "$PREFIX-ec2-role" 2>/dev/null || true
aws_ iam delete-instance-profile --instance-profile-name "$PREFIX-ec2-role" 2>/dev/null || true
for arn in arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly \
  arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore; do
  aws_ iam detach-role-policy --role-name "$PREFIX-ec2-role" --policy-arn "$arn" 2>/dev/null || true
done
aws_ iam delete-role --role-name "$PREFIX-ec2-role" 2>/dev/null || true

rm -f "$STATE_FILE"
log "Done. Confirm in the Billing console that nothing is still running."
