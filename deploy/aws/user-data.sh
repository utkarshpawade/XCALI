#!/bin/bash
# EC2 user-data for Amazon Linux 2023. Runs once, as root, at first boot.
# Installs Docker and prepares /opt/excalidraw; the stack itself is started by
# deploy.sh once images exist in ECR.
set -euxo pipefail

exec > >(tee /var/log/excalidraw-bootstrap.log) 2>&1

dnf update -y
dnf install -y docker

systemctl enable --now docker
usermod -aG docker ec2-user

# Compose v2 ships as a CLI plugin, not in the AL2023 repos.
COMPOSE_VERSION=v2.32.4
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL \
  "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

# 1 GB of RAM has to hold the JVM backend, the Next.js server and Caddy. Swap turns a hard
# OOM kill into a slowdown, which on a demo box is the better failure.
if [ ! -f /swapfile ]; then
  dd if=/dev/zero of=/swapfile bs=1M count=2048
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >>/etc/fstab
fi
sysctl -w vm.swappiness=10
echo 'vm.swappiness=10' >/etc/sysctl.d/99-swappiness.conf

install -d -o ec2-user -g ec2-user /opt/excalidraw

touch /var/lib/cloud/instance/excalidraw-bootstrap-done
