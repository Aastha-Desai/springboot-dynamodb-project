#!/usr/bin/env bash
set -euo pipefail
AWS_ACCOUNT_ID=$1
AWS_REGION=$2
ECR_REPO=$3
IMAGE_TAG=$4

IMAGE_NAME=${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}

if ! ls target/*.jar >/dev/null 2>&1; then
  echo "No application jar found under target/. Run ./mvnw -B -DskipTests package before building the Docker image." >&2
  exit 2
fi

echo "Logging into ECR ${AWS_REGION} for account ${AWS_ACCOUNT_ID}"
aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

echo "Building Docker image ${IMAGE_NAME} for linux/amd64"
docker buildx build --platform linux/amd64 -t ${IMAGE_NAME} --push .

echo "IMAGE_URI=${IMAGE_NAME}"
