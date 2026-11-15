#!/bin/sh
set -eu
rm -f /tmp/billing-sqs-ready

# Mesmo mecanismo do Billing/Compose, com o contrato exigido pela OS real.
# Endpoint deliberadamente fixo: nunca provisionar filas na AWS.
python3 - <<'PYTHON'
import json
from pathlib import Path

import boto3

sqs = boto3.client("sqs", endpoint_url="http://localhost:4566",
                   region_name="us-east-1", aws_access_key_id="test",
                   aws_secret_access_key="test")
dlq_url = sqs.create_queue(QueueName="numberone-billing-events-dlq")["QueueUrl"]
sqs.set_queue_attributes(QueueUrl=dlq_url, Attributes={"MessageRetentionPeriod": "1209600"})
dlq_arn = sqs.get_queue_attributes(QueueUrl=dlq_url, AttributeNames=["QueueArn"])["Attributes"]["QueueArn"]
queue_url = sqs.create_queue(QueueName="numberone-billing-events")["QueueUrl"]
expected_url = "http://localstack:4566/queue/us-east-1/000000000000/numberone-billing-events"
if queue_url != expected_url:
    raise RuntimeError("Endereçamento SQS incompatível com a configuração Kind")
attributes = {
    "VisibilityTimeout": "60",
    "ReceiveMessageWaitTimeSeconds": "20",
    "MessageRetentionPeriod": "345600",
    "RedrivePolicy": json.dumps({"deadLetterTargetArn": dlq_arn, "maxReceiveCount": "5"}),
}
sqs.set_queue_attributes(QueueUrl=queue_url, Attributes=attributes)
actual = sqs.get_queue_attributes(QueueUrl=queue_url, AttributeNames=["All"])["Attributes"]
for key in ("VisibilityTimeout", "ReceiveMessageWaitTimeSeconds", "MessageRetentionPeriod"):
    if actual.get(key) != attributes[key]:
        raise RuntimeError("Atributos SQS incompatíveis com o consumidor OS")
redrive = json.loads(actual.get("RedrivePolicy", "{}"))
if (int(redrive.get("maxReceiveCount", 0)) != 5
        or redrive.get("deadLetterTargetArn") != dlq_arn
        or actual.get("FifoQueue", "false") != "false"):
    raise RuntimeError("Redrive ou tipo da fila SQS incompatível")
Path("/tmp/billing-sqs-ready").touch()
PYTHON

echo BILLING_SQS_READY
