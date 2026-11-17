#!/usr/bin/env bash
set -euo pipefail

: "${IMAGE_URI:?Defina IMAGE_URI com a imagem validada e seu digest sha256.}"

if [[ ! "$IMAGE_URI" =~ ^[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}$ ]]; then
  printf 'IMAGE_URI deve identificar uma imagem por digest sha256.\n' >&2
  exit 1
fi

render_directory="$(mktemp -d)"
trap 'rm -rf "$render_directory"' EXIT
cp -R "$(dirname "$0")/../k8s" "$render_directory/k8s"

approval_url="${BUDGET_APPROVAL_BASE_URL:-}"
if [[ -n "$approval_url" && ! "$approval_url" =~ ^https://[a-zA-Z0-9.-]+(:[0-9]+)?/api/public/orcamentos-ordem-servico/decisao$ ]]; then
  printf 'BUDGET_APPROVAL_BASE_URL deve ser a URL HTTPS completa da rota de decisão.\n' >&2
  exit 1
fi

jq -n --arg revision "${DEPLOYMENT_REVISION:-}" --arg image "${IMAGE_URI%@*}" --arg digest "${IMAGE_URI##*@}" --arg approvalUrl "$approval_url" '{
  apiVersion: "kustomize.config.k8s.io/v1beta1",
  kind: "Kustomization",
  resources: ["k8s/overlays/billing"],
  images: [{name: "image.invalid/numberone-billing-service", newName: $image, digest: $digest}]
} + (if $revision == "" then {} else {commonAnnotations: {"numberone/deploy-revision": $revision}} end)
  + (if $approvalUrl == "" then {} else {patches: [{
    target: {kind: "ConfigMap", name: "numberone-billing-config"},
    patch: ([{op: "replace", path: "/data/BUDGET_APPROVAL_BASE_URL", value: $approvalUrl}] | tojson)
  }]} end)' > "$render_directory/kustomization.yaml"

# O deploy fornece a conta validada e o SHA do artefato do workflow.
# Renderizações locais sem esses valores continuam possíveis para revisão.
if [[ -n "${AWS_ACCOUNT_ID:-}" ]]; then
  if [[ ! "${AWS_ACCOUNT_ID:-}" =~ ^[0-9]{12}$ || ! "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "AWS_ACCOUNT_ID e GITHUB_SHA devem conter a conta e o SHA válidos." >&2
    exit 1
  fi
  jq --arg account "$AWS_ACCOUNT_ID" --arg version "$GITHUB_SHA" \
    --arg deployment "numberone-billing" '
      .patches = ((.patches // []) + [{target: {kind: "Deployment", name: $deployment},
        patch: ([{op: "add", path: "/spec/template/spec/containers/0/env/-", value: {name: "AWS_ACCOUNT_ID", value: $account}},
                 {op: "add", path: "/spec/template/spec/containers/0/env/-", value: {name: "DD_VERSION", value: $version}}] | tojson)}])
    ' "$render_directory/kustomization.yaml" > "$render_directory/kustomization.updated"
  mv "$render_directory/kustomization.updated" "$render_directory/kustomization.yaml"
fi

kubectl kustomize "$render_directory"
