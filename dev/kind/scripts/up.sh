#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_cluster
check_credentials
check_commercial_key

# Renderiza antes de construir/aplicar; o arquivo inclui Secret e fica privado.
umask 077
render_dir=$(mktemp -d)
keep_render_diagnostics=false
cleanup() {
  if [[ "$keep_render_diagnostics" == true ]]; then
    # Descarta manifests completos ou parciais que podem conter o Secret.
    rm -f -- "$render_dir/infra.yaml" "$render_dir/all.yaml"
  else
    rm -rf -- "$render_dir"
  fi
}
trap cleanup EXIT
if ! k kustomize "$ROOT/k8s" >"$render_dir/infra.yaml" 2>"$render_dir/render.err"; then
  keep_render_diagnostics=true
  fail "Falha ao renderizar infraestrutura. Diagnóstico privado: $render_dir/render.err"
fi
if ! k kustomize "$ROOT" >"$render_dir/all.yaml" 2>"$render_dir/render.err"; then
  keep_render_diagnostics=true
  fail "Falha ao renderizar ambiente integrado. Diagnóstico privado: $render_dir/render.err"
fi

"$ROOT/scripts/build-images.sh"

k apply -f "$render_dir/infra.yaml"
k rollout status statefulset/postgres --timeout=360s
# Readiness só sucede após o hook validar o contrato e a existência da fila.
k rollout status deployment/localstack --timeout=360s
k rollout status deployment/localstack-dynamodb --timeout=360s

deployments=(numberone-inventory numberone-billing numberone-os)
declare -A previous_templates=()
for deployment in "${deployments[@]}"; do
  previous_templates[$deployment]=$(k get "deployment/$deployment" --ignore-not-found -o 'jsonpath={.spec.template}')
done

# A renderização integrada resolve o hash do Secret PostgreSQL nos três serviços.
k apply -f "$render_dir/all.yaml"
for deployment in "${deployments[@]}"; do
  if [[ -n "${previous_templates[$deployment]}" ]]; then
    current_template=$(k get "deployment/$deployment" -o 'jsonpath={.spec.template}')
    # Templates alterados já geram rollout com a imagem recém-carregada.
    # Só força restart quando o Deployment existia e o template permaneceu igual.
    if [[ "$current_template" == "${previous_templates[$deployment]}" ]]; then
      k rollout restart "deployment/$deployment"
    fi
  fi
  k rollout status "deployment/$deployment" --timeout=600s
done
"$ROOT/scripts/status.sh"

"$ROOT/scripts/smoke.sh"
