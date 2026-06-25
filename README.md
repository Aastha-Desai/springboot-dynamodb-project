# Spring Boot DynamoDB ECS Auto-Healing Demo

This project is a Spring Boot sample application plus an agent-driven CI/CD workflow for detecting, fixing, approving, deploying, validating, and rolling back bug fixes on AWS ECS.

## What The Project Does

The application exposes employee APIs that accept JSON payloads and store employee data in DynamoDB. It includes Swagger/OpenAPI documentation and a Jenkins pipeline that builds a Docker image, pushes it to Amazon ECR, deploys it to ECS Fargate, validates the deployment, and rolls back automatically if validation fails.

The agent workflow adds automation around bug handling:

1. `JavaEcsMonitorAgent` monitors ECS service/task health and audits bugs.
2. `JavaAutoFixAgent` applies a known validation fix, such as restoring `@Size(max = 20)` for `employeeId`.
3. `JavaPrApprovalAgent` creates a GitHub PR and sends an email notification for human approval.
4. `JavaOrchestratorAgent` runs the monitor, auto-fix, PR, and email agents as one coordinated workflow.
5. Jenkins deploys merged/approved changes to ECS.
6. `JavaValidateEcsDeploymentAgent` validates Swagger, employee POST/GET, and invalid `employeeId` handling.
7. `rollback_ecs.sh` restores the previous ECS task definition if validation fails.
8. Agent and deployment actions are audited in DynamoDB table `AgentAudit`.

## Tech Stack

- Java 17+
- Spring Boot
- DynamoDB
- H2 for local/test runs
- Swagger/OpenAPI
- Docker
- Amazon ECR
- Amazon ECS Fargate
- Jenkins
- Java command-line agents
- GitHub PR API
- SMTP email notification

## Project Layout

```text
dynamodb-demo/
  src/main/java/...          Spring Boot application
  src/main/java/.../agent/   Java ECS monitor, auto-fix, PR/email, validation, and orchestrator agents
  scripts/                   ECR push, ECS deploy, ECS rollback scripts
  ecs/                       ECS task definition template
  Jenkinsfile                Build, deploy, validate, rollback pipeline
```

## Required Local/AWS Setup

You need:

- AWS CLI configured with access to ECS, ECR, IAM, EC2 networking, and DynamoDB.
- Docker running locally or on the Jenkins host.
- Jenkins with credentials ID `aws` containing AWS access key and secret key.
- DynamoDB tables:
  - `Employees`
  - `AgentAudit`
- ECS cluster/service created, for example:
  - cluster: `dynamodb-demo-cluster`
  - service: `dynamodb-demo-service`
  - task family: `dynamodb-demo-task`
- ECR repository:
  - `dynamodb-demo`

## GitHub Token And Email Approval Setup

The PR/email agent does not store secrets in code. Each user must provide their own token and email credentials through environment variables or CI/Jenkins secrets.

Set GitHub token:

```bash
export GITHUB_TOKEN="your_github_token"
```

`GH_TOKEN` also works:

```bash
export GH_TOKEN="your_github_token"
```

Set approval email variables:

```bash
export APPROVAL_EMAIL_TO="approver@example.com"
export EMAIL_FROM="your-email@example.com"
export SMTP_HOST="smtp.gmail.com"
export SMTP_PORT="587"
export SMTP_USERNAME="your-email@example.com"
export SMTP_PASSWORD="your_app_password"
```

For Gmail, `SMTP_PASSWORD` should be a Google app password, not your normal Google account password.

Check that variables are visible in your terminal:

```bash
echo "$GITHUB_TOKEN"
echo "$APPROVAL_EMAIL_TO"
echo "$SMTP_HOST"
echo "$SMTP_USERNAME"
```

Do not commit these values.

## Run The App Locally

The `local` Spring profile uses an in-memory H2 database, so you can run and test the API without AWS or local DynamoDB.

From the project root:

```bash
cd dynamodb-demo
./mvnw test
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

Local H2 console:

```text
http://localhost:8080/h2-console
```

Use this JDBC URL:

```text
jdbc:h2:mem:employees-local
```

## Run The Java Coordinated Agent Workflow

The main automation entry point is:

```bash
cd dynamodb-demo

./mvnw -q exec:java \
  -Dexec.mainClass=com.example.dynamodb_demo.agent.JavaOrchestratorAgent \
  -Dexec.args='\
  --email-mode smtp \
  --fix-branch agent-auto-fix \
  --paths dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java \
  --pr-title "Agent fix: restore employee validation" \
  --summary "The Java orchestrator detected a validation issue, applied an auto-fix, created a PR, and emailed human approval."'
```

For a local/demo run without ECS monitoring:

```bash
./mvnw -q exec:java \
  -Dexec.mainClass=com.example.dynamodb_demo.agent.JavaOrchestratorAgent \
  -Dexec.args='\
  --skip-monitor \
  --email-mode smtp \
  --fix-branch agent-auto-fix \
  --paths dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java'
```

What it does:

```text
monitor ECS
auto-fix known validation issue
run tests
detect changed source files
create GitHub PR
send approval email
write audit records
```

If there are no source changes, it will not create a PR or send an approval email.

## Test The Orchestrator With A Controlled Bug

Create a branch with the bug:

```bash
git switch -c orchestrator-bug-base-test
```

Temporarily remove this annotation from `Employee.java`:

```java
@Size(max = 20)
```

Commit and push the broken branch:

```bash
git add dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java
git commit -m "test: remove employee id validation"
git push -u origin orchestrator-bug-base-test
```

Run the orchestrator:

```bash
cd dynamodb-demo

./mvnw -q exec:java \
  -Dexec.mainClass=com.example.dynamodb_demo.agent.JavaOrchestratorAgent \
  -Dexec.args='\
  --skip-monitor \
  --email-mode smtp \
  --base-branch orchestrator-bug-base-test \
  --fix-branch orchestrator-validation-fix-test \
  --paths dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java \
  --pr-title "Agent orchestrator fix: restore employeeId validation" \
  --summary "The Java orchestrator detected the missing employeeId validation, auto-fixed it, created a PR, and emailed human approval."'
```

Expected result:

```text
Applied automatic fix
Tests passed after auto-fix
Pull request ready: https://github.com/...
Email notification sent to ...
```

## Jenkins Deployment Flow

After a human approves and merges the PR into `main`, Jenkins should deploy the approved change.

If Jenkins is local, enable polling:

```text
Job -> Configure -> Build Triggers -> Poll SCM
```

Use this schedule for testing:

```text
* * * * *
```

When Jenkins detects the merge, the build should say:

```text
Started by an SCM change
```

Normal Jenkins parameters:

```text
IMAGE_TAG = blank
FORCE_VALIDATION_FAILURE = false
```

Leaving `IMAGE_TAG` blank makes Jenkins use an immutable build tag like:

```text
build-18
```

## Jenkins Shell Scripts

The `.sh` files are used by Jenkins as pipeline helpers. They are not the primary manual deployment entry point.

Jenkins calls:

```text
scripts/ecr_login_and_push.sh    Build & Push Image stage
scripts/deploy_ecs.sh            Deploy to ECS stage
scripts/rollback_ecs.sh          Rollback path when validation fails
```

The expected production-style flow is:

```text
merge approved PR into main
Jenkins detects the SCM change
Jenkins builds and tests the Java application
Jenkins runs the shell scripts to push/deploy/rollback
Java validation and monitor agents verify the ECS deployment
```

Manual script runs are only for troubleshooting. For normal demo and project operation, run Jenkins.

## Validate Rollback

To test rollback intentionally, run Jenkins with:

```text
FORCE_VALIDATION_FAILURE = true
IMAGE_TAG = blank
```

Expected proof in Jenkins:

```text
Previous ECS task definition: arn:aws:ecs:...
Deployment validation failed: Forced validation failure requested for rollback test.
Rollback completed and audited.
Deployment validation succeeded: Swagger, POST/GET employee, and long employeeId validation passed.
```

The build may end as failed during this forced test. That is expected because the new deployment was rejected, but ECS was restored to the previous working task definition.

## Audit Check

Check `AgentAudit`:

```bash
AWS_PAGER="" aws dynamodb scan \
  --table-name AgentAudit \
  --region us-east-1 \
  --projection-expression "eventTime,eventType,#s,message" \
  --expression-attribute-names '{"#s":"status"}' \
  --query 'Items[*].[eventTime.S,eventType.S,status.S,message.S]' \
  --output table
```

Useful audit events:

```text
BUG_DETECTED
BUG_FIXED_AUTOMATICALLY
AUTO_FIX_VALIDATED
PR_CREATED
EMAIL_NOTIFICATION_SENT
FIX_COMMITTED_FOR_APPROVAL
DEPLOYMENT_VALIDATED
DEPLOYMENT_VALIDATION_FAILED
DEPLOYMENT_ROLLED_BACK
```

## Reuse On Another Repository

The Java agents are configured with command-line arguments and environment variables, so another repository can reuse the same workflow by changing:

- `repo_dir`
- `project_dir`
- `github_repo`
- `cluster`
- `service`
- `paths`
- PR title/body/summary
- email mode and SMTP/GitHub token environment variables

Example:

```bash
cd dynamodb-demo
./mvnw -q exec:java \
  -Dexec.mainClass=com.example.dynamodb_demo.agent.JavaOrchestratorAgent \
  -Dexec.args='--project-dir . --repo-dir .. --github-repo owner/repo --cluster your-cluster --service your-service --paths path/to/Employee.java --email-mode smtp'
```

The new repository owner must provide their own `GITHUB_TOKEN` and SMTP/email environment variables.
