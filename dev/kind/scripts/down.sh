#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_kubeconfig

stop_workload() {
  local workload=$1 selector=$2 existing pods
  existing=$(k get "$workload" --ignore-not-found -o name)
  [[ -n "$existing" ]] || return 0
  k scale "$workload" --replicas=0
  pods=$(k get pods -l "$selector" -o name)
  if [[ -n "$pods" ]]; then
    k wait --for=delete pod -l "$selector" --timeout=180s
  fi
}

# Interrompe somente Java; mantém PostgreSQL e LocalStack, incluindo SQS/DLQ.
stop_workload deployment/numberone-os app.kubernetes.io/name=numberone-service-order
stop_workload deployment/numberone-billing app.kubernetes.io/name=numberone-billing-service
stop_workload deployment/numberone-inventory app.kubernetes.io/name=numberone-inventory-service
echo 'Aplicações Java paradas; PostgreSQL e LocalStack continuam em execução.'
echo 'PVC, SQS/DLQ, configurações, namespace e cluster preservados pelo desligamento.'
