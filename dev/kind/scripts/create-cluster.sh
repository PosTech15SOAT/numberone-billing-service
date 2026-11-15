#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_tools kind docker
clusters=$(kind get clusters)
if [[ $'\n'"$clusters"$'\n' == *$'\n'"$CLUSTER"$'\n'* ]]; then
  echo "Cluster $CLUSTER já existe; preservado. Defina KIND_KUBECONFIG para seu kubeconfig."
  exit 0
fi
# Nunca substitui um kubeconfig existente de outro cluster.
[[ ! -e "$KUBE_CONFIG" ]] || fail "Kubeconfig já existe; selecione um novo KIND_KUBECONFIG"
mkdir -p "$(dirname -- "$KUBE_CONFIG")"
umask 077
kind create cluster --name "$CLUSTER" --config "$ROOT/cluster.yaml" --kubeconfig "$KUBE_CONFIG"
chmod 600 "$KUBE_CONFIG"
