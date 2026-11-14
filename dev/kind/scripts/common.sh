#!/usr/bin/env bash
# Biblioteca compartilhada; não executa operações ao ser carregada.
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
WORKSPACES=$(cd -- "$ROOT/../../.." && pwd)
KUBE_CONFIG="${KIND_KUBECONFIG:-$HOME/.kube/numberone-kind-config}"
CONTEXT=kind-numberone-local
CLUSTER=numberone-local
NAMESPACE=numberone-local
export KUBECONFIG="$KUBE_CONFIG"

fail() { echo "Erro: $*" >&2; exit 1; }

require_tools() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null || fail "Pré-requisito ausente: $tool"
  done
}

k() {
  kubectl --kubeconfig "$KUBE_CONFIG" --context "$CONTEXT" --namespace "$NAMESPACE" "$@"
}

check_kubeconfig() {
  require_tools kubectl
  [[ -r "$KUBE_CONFIG" ]] || fail "Kubeconfig local ausente: $KUBE_CONFIG"
  local server cluster
  server=$(k config view --minify -o 'jsonpath={.clusters[0].cluster.server}')
  cluster=$(k config view --minify -o 'jsonpath={.contexts[0].context.cluster}')
  [[ "$cluster" == "$CONTEXT" ]] || fail "Contexto não aponta ao cluster Kind esperado"
  [[ "$server" =~ ^https://127\.0\.0\.1:[0-9]+$ ]] || fail "API Kubernetes deve usar loopback local"
}

check_cluster() {
  check_kubeconfig
  require_tools docker kind
  docker info >/dev/null 2>&1 || fail "Docker indisponível"
  local clusters
  clusters=$(kind get clusters)
  [[ $'\n'"$clusters"$'\n' == *$'\n'"$CLUSTER"$'\n'* ]] ||
    fail "Cluster $CLUSTER ausente; execute scripts/create-cluster.sh para criar o ambiente"
  k get node "$CLUSTER-control-plane" >/dev/null
  k wait --for=condition=Ready "node/$CLUSTER-control-plane" --timeout=60s
  k get storageclass standard >/dev/null
}

check_credentials() {
  local file="$ROOT/k8s/postgres/.env" key value previous
  [[ -f "$file" ]] || fail "Copie k8s/postgres/.env.example para .env e preencha as senhas"
  local -A passwords=()
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    case "$key" in
      POSTGRES_PASSWORD|OS_PASSWORD|BILLING_PASSWORD|INVENTORY_PASSWORD) ;;
      *) fail "Chave inesperada no .env PostgreSQL" ;;
    esac
    [[ -n "$value" && ! "$value" =~ ^[[:space:]] && ! "$value" =~ [[:space:]]$ &&
       "$value" != \"* && "$value" != \'* && "$value" != *$'\r'* ]] ||
      fail "Senhas devem ser não vazias, sem aspas ou espaços nas extremidades"
    [[ ! -v passwords[$key] ]] || fail "Chave duplicada no .env PostgreSQL"
    for previous in "${passwords[@]}"; do
      [[ "$previous" != "$value" ]] || fail "Use quatro senhas distintas"
    done
    passwords[$key]=$value
  done < "$file"
  [[ ${#passwords[@]} == 4 ]] || fail "Preencha as quatro chaves do .env.example"
}

check_commercial_key() {
  require_tools python3
  python3 - "$ROOT/.env" <<'PYTHON'
from pathlib import Path
import base64, sys
p = Path(sys.argv[1])
if not p.is_file():
    raise SystemExit("Preencha dev/kind/.env com a chave comercial local.")
lines = [line for line in p.read_text().splitlines() if line and not line.startswith("#")]
if len(lines) != 1 or not lines[0].startswith("BUDGET_NOTIFICATION_ENCRYPTION_KEY="):
    raise SystemExit("Informe somente BUDGET_NOTIFICATION_ENCRYPTION_KEY no .env Kind.")
try:
    key = base64.b64decode(lines[0].split("=", 1)[1], validate=True)
except ValueError:
    raise SystemExit("Chave comercial local deve usar base64 válido.") from None
if len(key) != 32:
    raise SystemExit("Chave comercial local deve conter 32 bytes.")
PYTHON
}
