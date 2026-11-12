#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f docker-compose.sonar.yml up -d --wait --wait-timeout 360
printf 'SonarQube disponível em http://localhost:%s\n' "${SONAR_PORT:-9000}"
printf 'No primeiro acesso, altere a senha padrão de admin e gere um token em My Account > Security.\n'
