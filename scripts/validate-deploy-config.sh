#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PYTHON'
import os
import re
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
approval_url = require("BUDGET_APPROVAL_BASE_URL")
try:
    parsed_approval_url = urlsplit(approval_url)
    port = parsed_approval_url.port
    host = parsed_approval_url.hostname or ""
    valid_host = all(re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?", label)
                     for label in host.split(".")) and len(host) <= 253
except ValueError:
    valid_host = False
    port = None
if (not re.fullmatch(r"https://[a-zA-Z0-9.-]+(?::[0-9]+)?/api/public/orcamentos-ordem-servico/decisao", approval_url)
        or not valid_host or port == 0):
    raise SystemExit("BUDGET_APPROVAL_BASE_URL exige URL HTTPS completa da rota de decisão.")
notifications_enabled = boolean("BUDGET_NOTIFICATIONS_ENABLED")
checkout_enabled = boolean("MERCADO_PAGO_ENABLED")
webhook_enabled = boolean("MERCADO_PAGO_WEBHOOK_ENABLED")
if checkout_enabled:
    if require("MERCADO_PAGO_ENVIRONMENT") != "TEST":
        raise SystemExit("Checkout Mercado Pago exige ambiente TEST.")
    seller = require("MERCADO_PAGO_EXPECTED_TEST_SELLER_ID")
    if not re.fullmatch(r"[1-9][0-9]{0,18}", seller) or int(seller) > 9223372036854775807:
        raise SystemExit("Identificador esperado do vendedor inválido.")
    token = require("MERCADO_PAGO_TEST_ACCESS_TOKEN")
    if len(token) > 4096 or not re.fullmatch(r"(?:TEST-|APP_USR-)[A-Za-z0-9_-]+", token):
        raise SystemExit("Formato da credencial Mercado Pago inválido.")
if webhook_enabled:
    if require("MERCADO_PAGO_WEBHOOK_ENVIRONMENT") != "TEST":
        raise SystemExit("Webhook Mercado Pago exige ambiente TEST.")
    secret = require("MERCADO_PAGO_WEBHOOK_SECRET")
    if len(secret) > 4096 or secret != secret.strip() or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in secret):
        raise SystemExit("Segredo do webhook inválido.")
username, password = os.environ.get("SMTP_USERNAME", ""), os.environ.get("SMTP_PASSWORD", "")
if bool(username.strip()) != bool(password.strip()):
    raise SystemExit("SMTP_USERNAME e SMTP_PASSWORD devem ser configurados juntos.")
if notifications_enabled:
    for name in ("SMTP_HOST", "SMTP_PORT", "SMTP_FROM", "SMTP_USERNAME", "SMTP_PASSWORD"):
        if not require(name).strip():
            raise SystemExit(f"{name} obrigatório para notificações.")
    port = require("SMTP_PORT")
    if not port.isascii() or not port.isdigit() or not 1 <= int(port) <= 65535:
        raise SystemExit("SMTP_PORT deve ser uma porta válida.")
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
