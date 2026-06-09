# Part 3 — Jenkins / ECS Deployment (step-by-step)

This document shows the exact steps (CLI and console hints) you need to finish Part 3: build the Docker image, push to ECR, and deploy to AWS ECS using a Jenkins pipeline.

Prerequisites
- An AWS account with admin access (or privileges listed below).
- `aws` CLI configured locally (or Jenkins agent) and `docker` available on the Jenkins agent.
- A running Jenkins instance with an agent that can run Docker and `aws` commands.
- This repo (contains `Jenkinsfile`, `Dockerfile`, `scripts/`, and `ecs/task-definition.json.template`).

Overview
- Create ECR repo, ECS cluster, and task/execution role(s).
- Create an IAM user for Jenkins (programmatic), store its keys in Jenkins credentials.
- Configure Jenkins pipeline environment variables and run pipeline.

Minimum AWS resources and IAM requirements
- ECR repository (for Docker images)
- ECS cluster (Fargate)
- ECS Task execution role (name used in template: `ecsTaskExecutionRole`) with `AmazonECSTaskExecutionRolePolicy`
- IAM user for Jenkins with these managed policies: `AmazonEC2ContainerRegistryFullAccess`, `AmazonECS_FullAccess`, and `IAMFullAccess` or a narrower `iam:PassRole` that permits passing the execution role.

Recommended approach: use CLI to create resources quickly (commands below). If you prefer Console, the same items can be created using the AWS web console.

Step A — Create ECR repository
1. Decide on a repo name (example: `my-dynamodb-demo`).
2. CLI:
```bash
aws ecr create-repository --repository-name my-dynamodb-demo --region us-east-1
```
Note the repository name (set `ECR_REPO=my-dynamodb-demo`).

Step B — Create ECS cluster
```bash
aws ecs create-cluster --cluster-name my-demo-cluster --region us-east-1
```
Set `CLUSTER_NAME=my-demo-cluster`.

Step C — Create ECS Task Execution Role
1. Create a trust policy file `trust-policy.json` with the following content:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }
  ]
}
```

2. Create role and attach managed policy:
```bash
aws iam create-role --role-name ecsTaskExecutionRole --assume-role-policy-document file://trust-policy.json
aws iam attach-role-policy --role-name ecsTaskExecutionRole --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

Step D — Jenkins IAM user (programmatic credentials)
Create a Jenkins user (or use an existing user) and attach policies:

```bash
aws iam create-user --user-name jenkins-deploy
aws iam create-access-key --user-name jenkins-deploy
# Note the AccessKeyId and SecretAccessKey printed by the previous command.
# Attach policies (or create a custom minimal policy)
aws iam attach-user-policy --user-name jenkins-deploy --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess
aws iam attach-user-policy --user-name jenkins-deploy --policy-arn arn:aws:iam::aws:policy/AmazonECS_FullAccess
aws iam attach-user-policy --user-name jenkins-deploy --policy-arn arn:aws:iam::aws:policy/IAMFullAccess
```

Security note: `IAMFullAccess` is broad — prefer to create and attach a narrower policy that allows only `iam:PassRole` on the `ecsTaskExecutionRole` and the specific `ecr`/`ecs` actions required.

Step E — Create initial ECS service (optional)
You can create the service manually in the console or via CLI after you register a task. The pipeline `deploy_ecs.sh` registers a new task definition (from `ecs/task-definition.json.template`) and updates the existing service with `--force-new-deployment`. If you do not have a service yet, create one using your VPC subnets and security groups:

```bash
# You need subnet IDs and a security group that allows inbound traffic to your ALB or service.
aws ecs create-service \
  --cluster my-demo-cluster \
  --service-name my-demo-service \
  --task-definition <taskDefArn> \
  --desired-count 1 \
  --launch-type FARGATE \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-xxxx,subnet-yyyy],securityGroups=[sg-xxxx],assignPublicIp=ENABLED}' \
  --region us-east-1
```

Step F — Configure Jenkins
1. Decide where Jenkins will run:
  - Local on your Mac using Docker Desktop, or
  - On a shared Jenkins server / EC2 instance.

2. If you want to run Jenkins locally for the project demo, start it with Docker:
```bash
docker volume create jenkins_home
docker run --name jenkins \
  --restart unless-stopped \
  -p 8081:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  jenkins/jenkins:lts
```
Open `http://localhost:8081` in your browser.

3. Get the initial Jenkins admin password:
```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```
Paste that into Jenkins, then install the suggested plugins.

4. Install these Jenkins plugins if they are not already installed:
  - `Pipeline`
  - `Git`
  - `Credentials`
  - `Docker Pipeline`
  - `AWS Credentials` (optional, but helpful)

5. Add AWS credentials in Jenkins:
  - Manage Jenkins -> Credentials -> (global) -> Add Credentials
  - Kind: `Username with password`
  - ID: `aws`
  - Username: `<AccessKeyId from jenkins-user>`
  - Password: `<SecretAccessKey from jenkins-user>`

6. Create a new Pipeline job:
  - Jenkins Dashboard -> New Item
  - Name: `dynamodb-demo-pipeline`
  - Type: `Pipeline`
  - Pipeline definition: `Pipeline script from SCM`
  - SCM: `Git`
  - Repository URL: your repo URL
  - Branch: the branch containing `Jenkinsfile`

7. Add these pipeline environment variables in the job configuration (or in Jenkins global environment):
  - `AWS_ACCOUNT_ID=464868388928`
  - `ECR_REPO=dynamodb-demo`
  - `CLUSTER_NAME=dynamodb-demo-cluster`
  - `SERVICE_NAME=dynamodb-demo-service`
  - `TASK_FAMILY=dynamodb-demo-task`
  - `IMAGE_TAG=latest` (or leave blank to use the Jenkins build number)

8. Make sure the Jenkins agent has these tools:
  - `docker`
  - `aws`
  - network access to AWS
  - If Jenkins itself runs in Docker, the container image must include both CLIs or you must use a separate agent/VM that has them installed.
  - If you keep Jenkins in Docker, also mount the host Docker socket so the pipeline can build images.
  - The error `command not found` means the tool is missing from the Jenkins runtime, not from your project repo.

9. Run the pipeline:
  - Open the Jenkins job
  - Click `Build Now`
  - Watch the console output

10. What the Jenkins pipeline should do:
  - Build the JAR
  - Run tests
  - Build a Docker image
  - Push the image to ECR
  - Register a new ECS task definition
  - Update the ECS service to force a new deployment

11. Verify the deployment after Jenkins finishes:
  - Use the ECS service public IP or load balancer URL
  - Open Swagger UI in the browser
  - Call the employee endpoints with `curl`

12. If Jenkins fails on permissions:
  - Confirm the `aws` credential ID is exactly `aws`
  - Confirm `jenkins-user` has the attached policies in IAM
  - Confirm the Jenkins agent can run `docker` and `aws`

13. If you want a cleaner demo, use the repo files already included:
  - `Jenkinsfile`
  - `Dockerfile`
  - `scripts/ecr_login_and_push.sh`
  - `scripts/deploy_ecs.sh`
  - `ecs/task-definition.json.template`
 
 Step H — Do this next now (local Jenkins path)
 1. Make sure Docker Desktop is open and running.
 2. Start Jenkins in Docker:
 ```bash
 docker volume create jenkins_home
 docker run --name jenkins \
  --restart unless-stopped \
  -p 8081:8080 -p 50000:50000 \
  -v jenkins_home:/var/jenkins_home \
  jenkins/jenkins:lts
 ```
 3. Get the initial admin password:
 ```bash
 docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
 ```
 4. In your browser, open `http://localhost:8081` and paste that password.
 5. Click `Install suggested plugins`.
 6. Create the Jenkins admin user.
 7. Add AWS credentials in Jenkins:
   - Manage Jenkins -> Credentials -> Global -> Add Credentials
   - Kind: Username with password
   - ID: `aws`
   - Username: the AWS access key id for `jenkins-user`
   - Password: the AWS secret access key for `jenkins-user`
 8. Create a Pipeline job:
   - New Item -> `dynamodb-demo-pipeline`
   - Type: Pipeline
   - Pipeline definition: `Pipeline script from SCM`
   - SCM: Git
   - Repository: your Git repo URL
   - Branch: the branch with `Jenkinsfile`
 9. Add these job environment variables:
   - `AWS_ACCOUNT_ID=464868388928`
   - `ECR_REPO=dynamodb-demo`
   - `CLUSTER_NAME=dynamodb-demo-cluster`
   - `SERVICE_NAME=dynamodb-demo-service`
   - `TASK_FAMILY=dynamodb-demo-task`
   - `IMAGE_TAG=latest`
 10. Click `Build Now`.
 11. Watch the Console Output until all stages are green.
 12. If Jenkins asks for tool installation or agent setup, install/enable Docker and AWS CLI on that Jenkins machine/agent.
 
 What you do vs what I do:
 - You do: open Jenkins in the browser, paste the password, install plugins, create the credential, and click `Build Now`.
 - I did: create the Jenkinsfile, Dockerfile, ECS scripts, AWS infra, and the step-by-step instructions.
 
 If you want me to generate CloudFormation or the minimal IAM policy now, tell me which and I will add the files to the repo.

Step G — Run pipeline (what it does)
- `Build` stage: runs `./mvnw -DskipTests package` and builds the JAR.
- `Test` stage: runs unit tests.
- `Build & Push Image` stage: `scripts/ecr_login_and_push.sh` logs into ECR, builds and pushes the Docker image.
- `Deploy to ECS` stage: `scripts/deploy_ecs.sh` prepares a task-definition JSON from `ecs/task-definition.json.template`, registers it, and forces a new deployment of the ECS service.

Manual test (to verify everything works before Jenkins):
1. Build and push image locally (replace values):
```bash
AWS_ACCOUNT_ID=123456789012
AWS_REGION=us-east-1
ECR_REPO=my-dynamodb-demo
IMAGE_TAG=test1

aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
docker build -t ${ECR_REPO}:${IMAGE_TAG} .
docker tag ${ECR_REPO}:${IMAGE_TAG} ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}
```

2. Register a task definition using the template (replace placeholders):
```bash
sed "s|{{IMAGE_URI}}|${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}:${IMAGE_TAG}|g; s|{{TASK_FAMILY}}|dynamodb-demo-task|g" ecs/task-definition.json.template > /tmp/taskdef.json
aws ecs register-task-definition --cli-input-json file:///tmp/taskdef.json --region ${AWS_REGION}
```

3. Update service (force new deployment):
```bash
aws ecs update-service --cluster my-demo-cluster --service my-demo-service --force-new-deployment --region ${AWS_REGION}
```

4. Validate: after deployment, use the service's load balancer (or public IP) to hit the endpoints. Or check the task logs in CloudWatch.

Troubleshooting notes
- If Jenkins cannot push to ECR: verify the IAM user has `ecr:*` permissions and the account ID and region are correct.
- If ECS service does not start tasks: check subnets, security groups, task execution role (`iam:PassRole`), and CloudWatch logs.
- If you prefer infrastructure as code, I can add CloudFormation to create: an ECR repo, ECS cluster, task role, minimal VPC with two public subnets, security groups, and an ECS service.

Next steps I can do for you (pick one):
- Generate CloudFormation to provision ECR, ECS cluster, roles, VPC and service.
- Generate a minimal, safe IAM policy that grants only the permissions Jenkins needs (instead of `IAMFullAccess`).
- Help create the Jenkins job step-by-step or sample Jenkins job configuration screenshot/text.
- Add a simple Jenkinsfile example that includes automatic rollback checks after ECS deploy.

---
File references in this repo:
- `Jenkinsfile` - pipeline to use
- `Dockerfile` - image build
- `scripts/ecr_login_and_push.sh` - build/push helper
- `scripts/deploy_ecs.sh` - registers task definition and updates service
- `ecs/task-definition.json.template` - task definition template used by the script

If you want me to generate CloudFormation or the minimal IAM policy now, tell me which and I will add the files to the repo.
