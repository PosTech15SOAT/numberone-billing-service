#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_kubeconfig
require_tools curl
pids=()
cleanup() {
  local pid
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
umask 077
forward_dir=$(mktemp -d)
trap 'cleanup; rm -rf -- "$forward_dir"' EXIT
services=(numberone-os-service numberone-billing-service numberone-inventory-service)
local_ports=("${OS_LOCAL_PORT:-18080}" "${BILLING_LOCAL_PORT:-18081}" "${INVENTORY_LOCAL_PORT:-18082}")
remote_ports=(8080 8081 8082)
for index in 0 1 2; do
  k port-forward --address 127.0.0.1 "service/${services[$index]}" \
    "${local_ports[$index]}:${remote_ports[$index]}" > "$forward_dir/$index.log" 2>&1 &
  pids+=("$!")
done
for index in 0 1 2; do
  ready=false
  for _ in {1..30}; do
    kill -0 "${pids[$index]}" 2>/dev/null || fail "Port-forward encerrou; verifique porta ${local_ports[$index]}"
    if curl -fsS --max-time 2 "http://127.0.0.1:${local_ports[$index]}/actuator/health/readiness" >/dev/null 2>&1; then
      ready=true; break
    fi
    sleep 1
  done
  [[ "$ready" == true ]] || fail "Health indisponível na porta ${local_ports[$index]}"
done
echo "OS=${local_ports[0]} Billing=${local_ports[1]} Inventory=${local_ports[2]} em 127.0.0.1. Ctrl-C encerra os três forwards."
# Mantém o grupo em primeiro plano e remove somente processos criados aqui.
wait -n "${pids[@]}"
