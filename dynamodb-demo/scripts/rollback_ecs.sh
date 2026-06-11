#!/usr/bin/env bash
set -euo pipefail
export AWS_PAGER=""

AWS_REGION=$1
CLUSTER_NAME=$2
SERVICE_NAME=$3
PREVIOUS_TASK_DEFINITION=${4:-}
AUDIT_TABLE=${5:-AgentAudit}

aws_retry() {
  local max_attempts=5
  local delay_seconds=15
  local attempt=1

  until "$@"; do
    local exit_code=$?
    if [ "${attempt}" -ge "${max_attempts}" ]; then
      echo "AWS command failed after ${attempt} attempts: $*" >&2
      return "${exit_code}"
    fi

    echo "AWS command failed with exit code ${exit_code}; retrying in ${delay_seconds}s (${attempt}/${max_attempts}): $*" >&2
    sleep "${delay_seconds}"
    attempt=$((attempt + 1))
  done
}

audit_rollback() {
  local event_time
  local audit_id
  local message
  event_time=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  audit_id="rollback-${event_time//[^0-9]/}-${RANDOM}"
  message="Rolled back ECS service ${CLUSTER_NAME}/${SERVICE_NAME} to ${PREVIOUS_TASK_DEFINITION} after deployment validation failed."

  aws_retry aws dynamodb put-item \
    --table-name "${AUDIT_TABLE}" \
    --region "${AWS_REGION}" \
    --item "{
      \"auditId\": {\"S\": \"${audit_id}\"},
      \"eventTime\": {\"S\": \"${event_time}\"},
      \"source\": {\"S\": \"deployment-rollback-agent\"},
      \"eventType\": {\"S\": \"DEPLOYMENT_ROLLED_BACK\"},
      \"bugType\": {\"S\": \"DEPLOYMENT_VALIDATION\"},
      \"severity\": {\"S\": \"HIGH\"},
      \"status\": {\"S\": \"ROLLED_BACK\"},
      \"resource\": {\"S\": \"${CLUSTER_NAME}/${SERVICE_NAME}\"},
      \"message\": {\"S\": \"${message}\"}
    }"
}

if [ -z "${PREVIOUS_TASK_DEFINITION}" ] || [ "${PREVIOUS_TASK_DEFINITION}" = "None" ] || [ "${PREVIOUS_TASK_DEFINITION}" = "null" ]; then
  echo "Previous task definition is empty; refusing rollback." >&2
  exit 2
fi

if [[ "${PREVIOUS_TASK_DEFINITION}" != arn:aws:ecs:*:task-definition/* ]]; then
  echo "Previous task definition must be an ECS task definition ARN, got: ${PREVIOUS_TASK_DEFINITION}" >&2
  exit 2
fi

if [[ "${PREVIOUS_TASK_DEFINITION}" == *"PASTE_YOUR_TASK_DEFINITION_ARN_HERE"* ]]; then
  echo "Replace PASTE_YOUR_TASK_DEFINITION_ARN_HERE with a real ECS task definition ARN before running rollback." >&2
  exit 2
fi

echo "Rolling back ${CLUSTER_NAME}/${SERVICE_NAME} to ${PREVIOUS_TASK_DEFINITION}"
aws_retry aws ecs update-service \
  --cluster "${CLUSTER_NAME}" \
  --service "${SERVICE_NAME}" \
  --task-definition "${PREVIOUS_TASK_DEFINITION}" \
  --force-new-deployment \
  --region "${AWS_REGION}" \
  --output text \
  --query 'service.taskDefinition'

echo "Waiting for rolled back ECS service to become stable"
aws_retry aws ecs wait services-stable \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}"

audit_rollback
echo "Rollback completed and audited."
