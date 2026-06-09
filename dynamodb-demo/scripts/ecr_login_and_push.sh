#!/usr/bin/env bash
set -euo pipefail
AWS_ACCOUNT_ID=$1
AWS_REGION=$2
ECR_REPO=$3
IMAGE_TAG=$4

IMAGE_NAME=${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}

echo "Logging into ECR ${AWS_REGION} for account ${AWS_ACCOUNT_ID}"
aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

echo "Building Docker image ${IMAGE_NAME} for linux/amd64"
docker buildx build --platform linux/amd64 -t ${IMAGE_NAME} --push .

echo "IMAGE_URI=${IMAGE_NAME}"
