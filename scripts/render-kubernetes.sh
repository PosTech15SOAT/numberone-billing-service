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

jq -n --arg revision "${DEPLOYMENT_REVISION:-}" --arg image "${IMAGE_URI%@*}" --arg digest "${IMAGE_URI##*@}" '{
  apiVersion: "kustomize.config.k8s.io/v1beta1",
  kind: "Kustomization",
  resources: ["k8s/overlays/billing"],
  images: [{name: "image.invalid/numberone-billing-service", newName: $image, digest: $digest}]
} + (if $revision == "" then {} else {commonAnnotations: {"numberone/deploy-revision": $revision}} end)' > "$render_directory/kustomization.yaml"

kubectl kustomize "$render_directory"
