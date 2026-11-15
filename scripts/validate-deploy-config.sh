#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PYTHON'
import os
from urllib.parse import urlsplit

def require(name):
    value = os.environ.get(name, "")
    if not value:
        raise SystemExit(f"{name} obrigatório.")
    return value

def boolean(name):
    value = require(name)
    if value not in ("true", "false"):
        raise SystemExit(f"{name} deve ser true ou false.")
    return value == "true"

def queue_url(name):
    url = urlsplit(require(name))
    expected_host = f"sqs.{require('AWS_REGION')}.amazonaws.com"
    parts = url.path.strip("/").split("/")
    if (url.scheme != "https" or url.netloc != expected_host or url.query or url.fragment
            or len(parts) != 2 or parts[0] != require("AWS_ACCOUNT_ID")
            or not parts[1] or parts[1].endswith(".fifo")):
        raise SystemExit(f"{name} exige URL SQS Standard da conta/região configurada.")

import base64
try:
    key = base64.b64decode(require("BUDGET_NOTIFICATION_ENCRYPTION_KEY"), validate=True)
except ValueError:
    raise SystemExit("Chave comercial deve ser base64 válido.") from None
if len(key) != 32:
    raise SystemExit("Chave comercial exige 32 bytes após decodificação base64.")
if boolean("OUTBOX_ENABLED"):
    bridge = os.environ.get("SQS_BRIDGE_URL")
    if bridge:
        url = urlsplit(bridge)
        if (url.scheme != "https" or not url.hostname or url.username is not None
                or url.path not in ("", "/") or url.query or url.fragment):
            raise SystemExit("SQS_BRIDGE_URL exige a URL base HTTPS da ponte.")
    else:
        queue_url("SQS_QUEUE_URL")
PYTHON
