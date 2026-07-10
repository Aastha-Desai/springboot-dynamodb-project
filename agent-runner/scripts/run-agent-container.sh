#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IMAGE_NAME="${AGENT_IMAGE_NAME:-agent-runner:local}"

cd "${REPO_ROOT}/agent-runner"
docker build -t "${IMAGE_NAME}" .

docker run --rm \
  -v "${REPO_ROOT}:/workspace" \
  -w /workspace/agent-runner \
  -e AWS_ACCESS_KEY_ID \
  -e AWS_SECRET_ACCESS_KEY \
  -e AWS_SESSION_TOKEN \
  -e AWS_REGION \
  -e GITHUB_TOKEN \
  -e GH_TOKEN \
  -e APPROVAL_EMAIL_TO \
  -e EMAIL_FROM \
  -e SMTP_HOST \
  -e SMTP_PORT \
  -e SMTP_USERNAME \
  -e SMTP_PASSWORD \
  "${IMAGE_NAME}" "$@"
