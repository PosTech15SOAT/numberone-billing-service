#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_kubeconfig
k get namespace "$NAMESPACE"
k get deployments,statefulsets,pods,services,pvc,endpointslices -o wide
