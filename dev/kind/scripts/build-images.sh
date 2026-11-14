#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_cluster

for repo in numberone-service-order numberone-billing-service numberone-inventory-service; do
  [[ -f "$WORKSPACES/$repo/pom.xml" ]] || fail "Workspace ausente: $repo"
done

# Sequenciais: cada Dockerfile compila as fontes atuais, inclusive alterações locais.
# Nenhum JAR antigo de target/ é usado. Estes builds não substituem o CI/testes.
docker build --target local -t numberone-service-order:kind "$WORKSPACES/numberone-service-order"
docker build -f "$WORKSPACES/numberone-billing-service/Dockerfile.kind" \
  -t numberone-billing-service:kind "$WORKSPACES/numberone-billing-service"
docker build -t numberone-inventory-service:kind "$WORKSPACES/numberone-inventory-service"

for image in numberone-service-order numberone-billing-service numberone-inventory-service; do
  kind load docker-image --name "$CLUSTER" "$image:kind"
done
