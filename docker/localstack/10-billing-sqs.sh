#!/bin/sh
set -eu

# Apenas o emulador local; este script não provisiona recursos na AWS.
python3 - <<'PYTHON'
import json
import os
import boto3

sqs = boto3.client("sqs", endpoint_url="http://localhost:4566",
                   region_name=os.environ.get("AWS_DEFAULT_REGION", "us-east-1"))
dlq_url = sqs.create_queue(QueueName="numberone-billing-events-dlq")["QueueUrl"]
dlq_arn = sqs.get_queue_attributes(QueueUrl=dlq_url, AttributeNames=["QueueArn"])["Attributes"]["QueueArn"]
queue_url = sqs.create_queue(QueueName="numberone-billing-events")["QueueUrl"]
sqs.set_queue_attributes(QueueUrl=queue_url, Attributes={
    "RedrivePolicy": json.dumps({"deadLetterTargetArn": dlq_arn, "maxReceiveCount": "5"}),
    "VisibilityTimeout": "30"
})
PYTHON

touch /tmp/billing-sqs-ready
echo BILLING_SQS_READY
