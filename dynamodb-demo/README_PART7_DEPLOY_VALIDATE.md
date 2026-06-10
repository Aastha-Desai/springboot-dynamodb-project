# Part 7 - Approved Deployment Validation

This part validates that approved changes are deployed to AWS ECS and working.

## What happens after human approval

1. Human merges the agent PR into `main`.
2. Jenkins runs from `main`.
3. Jenkins builds and tests the Spring Boot app.
4. Jenkins builds and pushes the Docker image to ECR.
5. Jenkins deploys the new task definition to ECS.
6. Jenkins runs the deployment validation agent.
7. The validation result is audited in DynamoDB table `AgentAudit`.

## Validation checks

The deployment validation agent checks:

- `GET /v3/api-docs` returns Swagger/OpenAPI.
- `POST /api/employees` accepts a valid employee and writes to DynamoDB.
- `GET /api/employees/{id}` returns the employee.
- `POST /api/employees` with `employeeId > 20` returns HTTP `400`.

## Run locally

From `dynamodb-demo`:

```bash
python3 agent/validate_ecs_deployment.py \
  --region us-east-1 \
  --cluster dynamodb-demo-cluster \
  --service dynamodb-demo-service \
  --audit-table AgentAudit
```

Or with a known URL:

```bash
python3 agent/validate_ecs_deployment.py \
  --base-url http://18.206.91.114:8080
```

Expected success:

```text
Deployment validation succeeded: Swagger, POST/GET employee, and long employeeId validation passed.
```
