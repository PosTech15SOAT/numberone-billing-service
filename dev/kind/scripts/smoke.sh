#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
check_kubeconfig
require_tools curl python3
umask 077
smoke_dir=$(mktemp -d)
pids=()
cleanup() {
  local pid
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; done
  rm -rf -- "$smoke_dir"
}
trap cleanup EXIT
for deployment in numberone-inventory numberone-billing numberone-os localstack localstack-dynamodb; do
  k rollout status "deployment/$deployment" --timeout=60s
done
k rollout status statefulset/postgres --timeout=60s
services=(numberone-os-service numberone-billing-service numberone-inventory-service)
remote_ports=(8080 8081 8082)
for index in 0 1 2; do
  # Porta aleatória para não interferir em forwards já ativos.
  k port-forward --address 127.0.0.1 "service/${services[$index]}" ":${remote_ports[$index]}" > "$smoke_dir/$index.log" 2>&1 &
  pids+=("$!")
  port=""
  for _ in {1..30}; do
    kill -0 "${pids[$index]}" 2>/dev/null || fail "Falha no port-forward de smoke"
    port=$(sed -nE 's/Forwarding from 127.0.0.1:([0-9]+).*/\1/p' "$smoke_dir/$index.log" | head -1)
    [[ -z "$port" ]] || break
    sleep 1
  done
  [[ -n "$port" ]] || fail "Port-forward de smoke indisponível"
  for path in health/liveness health/readiness health; do
    curl -fsS --max-time 5 "http://127.0.0.1:$port/actuator/$path" | \
      python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
  done
  echo "${services[$index]}: health/liveness/readiness UP"
done
