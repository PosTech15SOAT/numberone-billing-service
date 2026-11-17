#!/bin/sh
set -eu
# Mantém as tags fornecidas pela plataforma ao acrescentar a conta de implantação.
if [ -n "${AWS_ACCOUNT_ID:-}" ]; then
  DD_TAGS="${DD_TAGS:+$DD_TAGS }aws_account_id:$AWS_ACCOUNT_ID"
  export DD_TAGS
fi
exec java -javaagent:/app/dd-java-agent.jar -jar /app/app.jar "$@"
