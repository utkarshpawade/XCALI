#!/usr/bin/env bash
# Provisions the AWS free-tier footprint for this project:
#
#   CloudFront (TLS + wss)  ->  EC2 t3.micro (Caddy + 3 containers)  ->  RDS db.t4g.micro
#
# Everything lands in the account's default VPC on purpose. A custom VPC would
# need private subnets, and private subnets tempt a NAT Gateway, which is ~$32/mo
# and would be the single largest line on the bill.
#
# Safe to re-run: every step checks for an existing resource first.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

# Git Bash (MSYS) rewrites arguments that look like absolute POSIX paths:
# /aws/service/... becomes C:/Program Files/Git/aws/service/... and the
# /dev/xvda in --block-device-mappings goes the same way. Exclude just those
# two shapes - a blanket '*' also breaks resolving the aws launcher itself.
# Unset outside MSYS, where it is simply ignored.
export MSYS2_ARG_CONV_EXCL='/aws/;DeviceName='

AWS="${AWS_CLI:-aws}"
REGION="${AWS_REGION:-ap-south-1}"
PROFILE_ARG=""
[[ -n "${AWS_PROFILE:-}" ]] && PROFILE_ARG="--profile ${AWS_PROFILE}"

PREFIX="${STACK_PREFIX:-excalidraw}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.micro}"
DB_INSTANCE_CLASS="${DB_INSTANCE_CLASS:-db.t4g.micro}"
DB_NAME=excalidraw
DB_USER=excalidraw
STATE_FILE=".state.env"

# The AWS CLI emits CRLF on Windows. A stray \r survives pipes and `read`,
# and AWS rejects it as a control character (RDS) or silently bakes it into
# values like the CloudFront domain and the .pem key, so strip it centrally.
aws_() { $AWS --region "$REGION" $PROFILE_ARG "$@" | tr -d '\r'; }
log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warn] %s\033[0m\n' "$*" >&2; }

# Persist a key=value pair so deploy.sh and re-runs can pick it up.
save() {
  touch "$STATE_FILE"
  grep -v "^$1=" "$STATE_FILE" >"$STATE_FILE.tmp" 2>/dev/null || true
  mv "$STATE_FILE.tmp" "$STATE_FILE"
  echo "$1=$2" >>"$STATE_FILE"
  export "$1=$2"
}

[[ -f "$STATE_FILE" ]] && source "$STATE_FILE"

# ---------------------------------------------------------------- preflight --

log "Checking credentials"
ACCOUNT_ID=$(aws_ sts get-caller-identity --query Account --output text)
echo "Account $ACCOUNT_ID in $REGION"
save ACCOUNT_ID "$ACCOUNT_ID"
save REGION "$REGION"

log "Locating the default VPC"
VPC_ID=$(aws_ ec2 describe-vpcs --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text)
if [[ "$VPC_ID" == "None" || -z "$VPC_ID" ]]; then
  echo "No default VPC in $REGION. Create one with:" >&2
  echo "  aws ec2 create-default-vpc --region $REGION" >&2
  exit 1
fi
save VPC_ID "$VPC_ID"

# RDS wants subnets in at least two availability zones.
mapfile -t SUBNETS < <(aws_ ec2 describe-subnets \
  --filters "Name=vpc-id,Values=$VPC_ID" "Name=default-for-az,Values=true" \
  --query 'Subnets[].SubnetId' --output text | tr '\t' '\n')
if ((${#SUBNETS[@]} < 2)); then
  echo "Need at least 2 default subnets, found ${#SUBNETS[@]}." >&2
  exit 1
fi
save EC2_SUBNET "${SUBNETS[0]}"

# ------------------------------------------------------------------ secrets --

if [[ -z "${JWT_SECRET:-}" ]]; then
  log "Generating JWT_SECRET"
  save JWT_SECRET "$(openssl rand -hex 48)"
fi
if [[ -z "${DB_PASSWORD:-}" ]]; then
  log "Generating database password"
  # RDS rejects /, @, ", and space in master passwords.
  save DB_PASSWORD "$(openssl rand -hex 24)"
fi

# ----------------------------------------------------------- security groups --

ensure_sg() {
  local name=$1 desc=$2 id
  id=$(aws_ ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=$VPC_ID" "Name=group-name,Values=$name" \
    --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo None)
  if [[ "$id" == "None" || -z "$id" ]]; then
    id=$(aws_ ec2 create-security-group --group-name "$name" \
      --description "$desc" --vpc-id "$VPC_ID" --query GroupId --output text)
  fi
  echo "$id"
}

log "Security groups"
EC2_SG=$(ensure_sg "$PREFIX-ec2" "Excalidraw web host")
RDS_SG=$(ensure_sg "$PREFIX-rds" "Excalidraw Postgres")
save EC2_SG "$EC2_SG"
save RDS_SG "$RDS_SG"

# Port 80 is opened only to CloudFront's origin-facing ranges, so the raw
# instance IP cannot be used to bypass TLS.
CF_PREFIX_LIST=$(aws_ ec2 describe-managed-prefix-lists \
  --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing \
  --query 'PrefixLists[0].PrefixListId' --output text 2>/dev/null || echo None)

if [[ "$CF_PREFIX_LIST" != "None" && -n "$CF_PREFIX_LIST" ]]; then
  aws_ ec2 authorize-security-group-ingress --group-id "$EC2_SG" \
    --ip-permissions "IpProtocol=tcp,FromPort=80,ToPort=80,PrefixListIds=[{PrefixListId=$CF_PREFIX_LIST}]" \
    >/dev/null 2>&1 || true
  echo "Port 80 restricted to CloudFront ($CF_PREFIX_LIST)"
else
  warn "CloudFront prefix list unavailable in $REGION; opening port 80 to 0.0.0.0/0"
  aws_ ec2 authorize-security-group-ingress --group-id "$EC2_SG" \
    --protocol tcp --port 80 --cidr 0.0.0.0/0 >/dev/null 2>&1 || true
fi

# SSH from this machine only.
MY_IP=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
aws_ ec2 authorize-security-group-ingress --group-id "$EC2_SG" \
  --protocol tcp --port 22 --cidr "$MY_IP/32" >/dev/null 2>&1 || true
echo "SSH restricted to $MY_IP/32"

# Postgres reachable only from the web host.
aws_ ec2 authorize-security-group-ingress --group-id "$RDS_SG" \
  --protocol tcp --port 5432 --source-group "$EC2_SG" >/dev/null 2>&1 || true

# ---------------------------------------------------------------------- ECR --

log "ECR repositories"
for repo in excalidraw-backend excalidraw-frontend; do
  aws_ ecr describe-repositories --repository-names "$repo" >/dev/null 2>&1 ||
    aws_ ecr create-repository --repository-name "$repo" \
      --image-scanning-configuration scanOnPush=false >/dev/null
  # Free tier is 500 MB; keep only the newest few images.
  aws_ ecr put-lifecycle-policy --repository-name "$repo" --lifecycle-policy-text \
    '{"rules":[{"rulePriority":1,"description":"keep 3","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":3},"action":{"type":"expire"}}]}' \
    >/dev/null 2>&1 || true
  echo "  $repo"
done
save ECR_REGISTRY "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"

# ---------------------------------------------------------------------- IAM --

log "Instance profile for ECR pulls"
ROLE="$PREFIX-ec2-role"
if ! aws_ iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  aws_ iam create-role --role-name "$ROLE" --assume-role-policy-document \
    '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' \
    >/dev/null
fi
aws_ iam attach-role-policy --role-name "$ROLE" \
  --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly >/dev/null 2>&1 || true
# Lets us reach the box via Session Manager if SSH ever breaks.
aws_ iam attach-role-policy --role-name "$ROLE" \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore >/dev/null 2>&1 || true

if ! aws_ iam get-instance-profile --instance-profile-name "$ROLE" >/dev/null 2>&1; then
  aws_ iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
  aws_ iam add-role-to-instance-profile --instance-profile-name "$ROLE" \
    --role-name "$ROLE" >/dev/null
  echo "Waiting for the instance profile to propagate"
  sleep 15
fi

# ---------------------------------------------------------------------- RDS --

log "RDS Postgres ($DB_INSTANCE_CLASS)"
SUBNET_GROUP="$PREFIX-db-subnets"
aws_ rds describe-db-subnet-groups --db-subnet-group-name "$SUBNET_GROUP" >/dev/null 2>&1 ||
  aws_ rds create-db-subnet-group --db-subnet-group-name "$SUBNET_GROUP" \
    --db-subnet-group-description "Excalidraw" \
    --subnet-ids "${SUBNETS[@]}" >/dev/null

DB_ID="$PREFIX-db"
if ! aws_ rds describe-db-instances --db-instance-identifier "$DB_ID" >/dev/null 2>&1; then
  PG_VERSION=$(aws_ rds describe-db-engine-versions --engine postgres --default-only \
    --query 'DBEngineVersions[0].EngineVersion' --output text)
  # backup-retention-period: accounts on the new credit-based Free Plan reject
  # anything above their cap with FreeTierRestrictionError. 0 disables automated
  # backups and point-in-time recovery; raise it on a paid plan.
  echo "Creating $DB_ID on postgres $PG_VERSION (this takes about 5-10 minutes)"
  aws_ rds create-db-instance \
    --db-instance-identifier "$DB_ID" \
    --db-instance-class "$DB_INSTANCE_CLASS" \
    --engine postgres --engine-version "$PG_VERSION" \
    --master-username "$DB_USER" --master-user-password "$DB_PASSWORD" \
    --db-name "$DB_NAME" \
    --allocated-storage 20 --storage-type gp2 \
    --db-subnet-group-name "$SUBNET_GROUP" \
    --vpc-security-group-ids "$RDS_SG" \
    --no-publicly-accessible \
    --backup-retention-period "${BACKUP_RETENTION:-7}" \
    --no-multi-az \
    --no-auto-minor-version-upgrade \
    --no-enable-performance-insights \
    >/dev/null
else
  echo "$DB_ID already exists"
fi

# ---------------------------------------------------------------------- EC2 --

log "EC2 instance ($INSTANCE_TYPE)"
KEY_NAME="$PREFIX-key"
KEY_FILE="$KEY_NAME.pem"
if ! aws_ ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
  aws_ ec2 create-key-pair --key-name "$KEY_NAME" \
    --query KeyMaterial --output text >"$KEY_FILE"
  chmod 600 "$KEY_FILE"
  echo "Private key written to deploy/aws/$KEY_FILE (gitignored, do not share)"
fi
save KEY_FILE "$KEY_FILE"

INSTANCE_ID=$(aws_ ec2 describe-instances \
  --filters "Name=tag:Name,Values=$PREFIX" "Name=instance-state-name,Values=pending,running,stopped" \
  --query 'Reservations[0].Instances[0].InstanceId' --output text 2>/dev/null || echo None)

if [[ "$INSTANCE_ID" == "None" || -z "$INSTANCE_ID" ]]; then
  AMI_ID=$(aws_ ssm get-parameters \
    --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --query 'Parameters[0].Value' --output text)
  echo "Launching from $AMI_ID"
  INSTANCE_ID=$(aws_ ec2 run-instances \
    --image-id "$AMI_ID" --instance-type "$INSTANCE_TYPE" \
    --key-name "$KEY_NAME" \
    --security-group-ids "$EC2_SG" \
    --subnet-id "${SUBNETS[0]}" \
    --iam-instance-profile "Name=$ROLE" \
    --user-data file://user-data.sh \
    --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3,DeleteOnTermination=true}' \
    --metadata-options 'HttpTokens=required,HttpEndpoint=enabled' \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$PREFIX}]" \
    --query 'Instances[0].InstanceId' --output text)
fi
save INSTANCE_ID "$INSTANCE_ID"

echo "Waiting for $INSTANCE_ID to run"
aws_ ec2 wait instance-running --instance-ids "$INSTANCE_ID"

# A static address means rebooting the box does not invalidate CloudFront's origin.
if [[ -z "${PUBLIC_IP:-}" ]]; then
  log "Allocating an Elastic IP"
  ALLOC_ID=$(aws_ ec2 allocate-address --domain vpc \
    --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$PREFIX}]" \
    --query AllocationId --output text)
  aws_ ec2 associate-address --instance-id "$INSTANCE_ID" \
    --allocation-id "$ALLOC_ID" >/dev/null
  save ALLOC_ID "$ALLOC_ID"
fi
PUBLIC_IP=$(aws_ ec2 describe-addresses --allocation-ids "$ALLOC_ID" \
  --query 'Addresses[0].PublicIp' --output text)
save PUBLIC_IP "$PUBLIC_IP"
echo "Public IP: $PUBLIC_IP"

# CloudFront will not accept a bare IP as an origin, and the EC2-assigned
# hostname tracks the Elastic IP, so it stays correct across reboots without
# depending on a third-party wildcard DNS service.
PUBLIC_DNS=$(aws_ ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicDnsName' --output text)
if [[ -z "$PUBLIC_DNS" || "$PUBLIC_DNS" == "None" ]]; then
  echo "Instance has no public DNS name; enable DNS hostnames on $VPC_ID." >&2
  exit 1
fi
save PUBLIC_DNS "$PUBLIC_DNS"
echo "Origin hostname: $PUBLIC_DNS"

# --------------------------------------------------------------- CloudFront --

# CloudFront needs an account AWS has verified, and brand new accounts are
# refused until that clears. Setting PUBLIC_DOMAIN skips CloudFront entirely
# and lets Caddy terminate TLS on the instance instead (see the Caddyfile).
if [[ -n "${PUBLIC_DOMAIN:-}" ]]; then
  log "Public domain (CloudFront skipped)"
elif [[ -z "${CF_DOMAIN:-}" ]]; then
  log "CloudFront distribution"
  # Managed policy IDs are global constants.
  CACHE_DISABLED=4135ea2d-6df8-44a3-9df3-4b5a84be39ad
  CACHE_OPTIMIZED=658327ea-f89d-4fab-a63d-7e88639e58f6
  # Forwards every viewer header except Host, which includes the Sec-WebSocket-*
  # headers the upgrade handshake needs.
  ORIGIN_ALL=b689b0a8-53d0-40ab-baf2-68738e2966ac

  ALL_METHODS='{"Quantity":7,"Items":["GET","HEAD","OPTIONS","PUT","POST","PATCH","DELETE"],"CachedMethods":{"Quantity":2,"Items":["GET","HEAD"]}}'
  READ_METHODS='{"Quantity":2,"Items":["GET","HEAD"],"CachedMethods":{"Quantity":2,"Items":["GET","HEAD"]}}'

  # Written next to this script rather than /tmp: Git Bash rewrites absolute
  # paths inside file:// arguments before the CLI ever sees them.
  cat >cf-config.json <<JSON
{
  "CallerReference": "$PREFIX-$(date +%s)",
  "Comment": "Excalidraw clone",
  "Enabled": true,
  "HttpVersion": "http2",
  "PriceClass": "PriceClass_100",
  "Origins": {
    "Quantity": 1,
    "Items": [{
      "Id": "ec2-origin",
      "DomainName": "$PUBLIC_DNS",
      "CustomOriginConfig": {
        "HTTPPort": 80,
        "HTTPSPort": 443,
        "OriginProtocolPolicy": "http-only",
        "OriginSslProtocols": {"Quantity": 1, "Items": ["TLSv1.2"]},
        "OriginReadTimeout": 60,
        "OriginKeepaliveTimeout": 60
      }
    }]
  },
  "DefaultCacheBehavior": {
    "TargetOriginId": "ec2-origin",
    "ViewerProtocolPolicy": "redirect-to-https",
    "AllowedMethods": $ALL_METHODS,
    "CachePolicyId": "$CACHE_DISABLED",
    "OriginRequestPolicyId": "$ORIGIN_ALL",
    "Compress": true
  },
  "CacheBehaviors": {
    "Quantity": 3,
    "Items": [
      {
        "PathPattern": "/_next/static/*",
        "TargetOriginId": "ec2-origin",
        "ViewerProtocolPolicy": "redirect-to-https",
        "AllowedMethods": $READ_METHODS,
        "CachePolicyId": "$CACHE_OPTIMIZED",
        "Compress": true
      },
      {
        "PathPattern": "/api/*",
        "TargetOriginId": "ec2-origin",
        "ViewerProtocolPolicy": "redirect-to-https",
        "AllowedMethods": $ALL_METHODS,
        "CachePolicyId": "$CACHE_DISABLED",
        "OriginRequestPolicyId": "$ORIGIN_ALL",
        "Compress": true
      },
      {
        "PathPattern": "/ws*",
        "TargetOriginId": "ec2-origin",
        "ViewerProtocolPolicy": "redirect-to-https",
        "AllowedMethods": $ALL_METHODS,
        "CachePolicyId": "$CACHE_DISABLED",
        "OriginRequestPolicyId": "$ORIGIN_ALL",
        "Compress": false
      }
    ]
  }
}
JSON

  read -r cf_id cf_domain < <(aws_ cloudfront create-distribution \
    --distribution-config file://cf-config.json \
    --query 'Distribution.[Id,DomainName]' --output text)
  rm -f cf-config.json
  save CF_ID "$cf_id"
  save CF_DOMAIN "$cf_domain"
fi
# Whichever path ran above, one of these two holds the public hostname.
PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-${CF_DOMAIN:-}}"
save PUBLIC_DOMAIN "$PUBLIC_DOMAIN"
save PUBLIC_ORIGIN "https://$PUBLIC_DOMAIN"
echo "Public origin: https://$PUBLIC_DOMAIN"

# ------------------------------------------------------------------- finish --

log "Waiting for RDS to become available"
aws_ rds wait db-instance-available --db-instance-identifier "$DB_ID"
DB_HOST=$(aws_ rds describe-db-instances --db-instance-identifier "$DB_ID" \
  --query 'DBInstances[0].Endpoint.Address' --output text)
save DB_HOST "$DB_HOST"
save DATABASE_URL "postgresql://$DB_USER:$DB_PASSWORD@$DB_HOST:5432/$DB_NAME?schema=public"

log "Provisioning complete"
cat <<SUMMARY

  Public URL     https://$PUBLIC_DOMAIN
  EC2            $INSTANCE_ID at $PUBLIC_IP
  Database       $DB_HOST
  SSH            ssh -i deploy/aws/$KEY_FILE ec2-user@$PUBLIC_IP

  State written to deploy/aws/$STATE_FILE (contains secrets; gitignored).
  Next: ./deploy.sh

SUMMARY
