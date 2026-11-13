#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${SONAR_TOKEN:?Defina SONAR_TOKEN com um token do SonarQube local.}"
if [[ ! "${SONAR_PORT:-9000}" =~ ^[0-9]{1,5}$ ]] || (( 10#${SONAR_PORT:-9000} < 1 || 10#${SONAR_PORT:-9000} > 65535 )); then
  printf 'SONAR_PORT deve ser uma porta entre 1 e 65535.\n' >&2
  exit 1
fi
export SONAR_HOST_URL="http://localhost:${SONAR_PORT:-9000}"
if ! curl --max-time 10 -fsS "$SONAR_HOST_URL/api/system/status" | grep -q '"status":"UP"'; then
  printf 'SonarQube indisponível. Execute scripts/sonar-up.sh.\n' >&2
  exit 1
fi
"${MVN_CMD:-./mvnw}" --batch-mode clean verify sonar:sonar \
  -Dsonar.host.url="$SONAR_HOST_URL" \
  -Dsonar.qualitygate.wait=true \
  -Dsonar.qualitygate.timeout=300
