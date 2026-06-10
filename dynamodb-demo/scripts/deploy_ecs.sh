#!/usr/bin/env bash
set -euo pipefail
AWS_ACCOUNT_ID=$1
AWS_REGION=$2
CLUSTER_NAME=$3
SERVICE_NAME=$4
TASK_FAMILY=$5
ECR_REPO=$6
IMAGE_TAG=$7

IMAGE_URI=${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}

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

echo "Preparing task definition from template"
TMPDEF=$(mktemp /tmp/taskdef.XXXX.json)
trap 'rm -f "${TMPDEF}"' EXIT
sed "s|{{IMAGE_URI}}|${IMAGE_URI}|g; s|{{TASK_FAMILY}}|${TASK_FAMILY}|g; s|{{AWS_ACCOUNT_ID}}|${AWS_ACCOUNT_ID}|g" ecs/task-definition.json.template > ${TMPDEF}

echo "Registering task definition"
TASK_DEFINITION_ARN=$(aws_retry aws ecs register-task-definition \
  --cli-input-json file://${TMPDEF} \
  --region ${AWS_REGION} \
  --query 'taskDefinition.taskDefinitionArn' \
  --output text)

echo "Updating service to use new task definition"
aws_retry aws ecs update-service \
  --cluster ${CLUSTER_NAME} \
  --service ${SERVICE_NAME} \
  --task-definition ${TASK_DEFINITION_ARN} \
  --force-new-deployment \
  --region ${AWS_REGION}

echo "Waiting for ECS service to become stable"
aws_retry aws ecs wait services-stable \
  --cluster ${CLUSTER_NAME} \
  --services ${SERVICE_NAME} \
  --region ${AWS_REGION}
