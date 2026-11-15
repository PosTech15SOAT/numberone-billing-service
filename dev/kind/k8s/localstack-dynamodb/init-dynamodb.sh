#!/bin/sh
set -eu
python3 - <<'PY'
import boto3
from botocore.exceptions import ClientError
client=boto3.client('dynamodb',endpoint_url='http://localhost:4566',region_name='us-east-1',aws_access_key_id='test',aws_secret_access_key='test')
name='vehicle_maintenance_history'
try:
 table=client.describe_table(TableName=name)['Table']
except client.exceptions.ResourceNotFoundException:
 client.create_table(TableName=name,KeySchema=[{'AttributeName':'vehicleId','KeyType':'HASH'},{'AttributeName':'historyKey','KeyType':'RANGE'}],AttributeDefinitions=[{'AttributeName':'vehicleId','AttributeType':'S'},{'AttributeName':'historyKey','AttributeType':'S'}],BillingMode='PAY_PER_REQUEST')
 client.get_waiter('table_exists').wait(TableName=name)
 table=client.describe_table(TableName=name)['Table']
assert {(x['AttributeName'],x['KeyType']) for x in table['KeySchema']}=={('vehicleId','HASH'),('historyKey','RANGE')}
assert {(x['AttributeName'],x['AttributeType']) for x in table['AttributeDefinitions']}=={('vehicleId','S'),('historyKey','S')}
print('HISTORY_DYNAMODB_READY')
PY
