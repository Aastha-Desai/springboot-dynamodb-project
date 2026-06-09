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

echo "Preparing task definition from template"
TMPDEF=$(mktemp /tmp/taskdef.XXXX.json)
sed "s|{{IMAGE_URI}}|${IMAGE_URI}|g; s|{{TASK_FAMILY}}|${TASK_FAMILY}|g; s|{{AWS_ACCOUNT_ID}}|${AWS_ACCOUNT_ID}|g" ecs/task-definition.json.template > ${TMPDEF}

echo "Registering task definition"
aws ecs register-task-definition --cli-input-json file://${TMPDEF} --region ${AWS_REGION}

echo "Updating service to use new task definition"
aws ecs update-service --cluster ${CLUSTER_NAME} --service ${SERVICE_NAME} --force-new-deployment --region ${AWS_REGION}

rm -f ${TMPDEF}
