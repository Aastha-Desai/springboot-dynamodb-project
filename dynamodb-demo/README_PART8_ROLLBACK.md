# Part 8 - Roll Back Failed ECS Deployments

Part 8 protects the running ECS service if a newly deployed version fails validation.

## What it does

1. Jenkins captures the currently running ECS task definition before deploying.
2. Jenkins deploys the approved/new version to ECS.
3. Jenkins runs the Part 7 validation agent.
4. If validation passes, the deployment stays live.
5. If validation fails, Jenkins runs `scripts/rollback_ecs.sh`.
6. The rollback script updates the ECS service back to the previous task definition and waits for ECS to become stable.
7. Jenkins validates the rolled-back service again.
8. The rollback action is audited in DynamoDB table `AgentAudit`.

## Files

- `Jenkinsfile`
  - Captures `PREVIOUS_TASK_DEFINITION`.
  - Uses a build-specific image tag by default so rollback points to the previous image, not a mutable `latest` tag.
  - Wraps validation in rollback logic.
  - Adds `FORCE_VALIDATION_FAILURE` for rollback testing.
- `scripts/rollback_ecs.sh`
  - Rolls ECS back to the previous task definition.
  - Waits for service stability.
  - Writes `DEPLOYMENT_ROLLED_BACK` to `AgentAudit`.
- `agent/validate_ecs_deployment.py`
  - Adds `--force-fail` so Part 8 can be tested without intentionally breaking the app.

## How to test rollback safely

Use the Jenkins parameter:

```text
FORCE_VALIDATION_FAILURE = true
```

Then run the Jenkins pipeline from `main`.

Expected behavior:

1. Build succeeds.
2. Docker image is pushed.
3. ECS deploy starts.
4. Validation fails on purpose.
5. Jenkins rolls ECS back to the previous task definition.
6. Jenkins validates the rolled-back ECS service.
7. DynamoDB `AgentAudit` contains:

```text
DEPLOYMENT_VALIDATION_FAILED | FAILED
DEPLOYMENT_ROLLED_BACK      | ROLLED_BACK
DEPLOYMENT_VALIDATED        | PASSED
```

The Jenkins build should still finish as failed because the new release was rejected, but the ECS service should be restored and functional.

## Important image tag note

Rollback works best when ECS task definitions point to immutable image tags. The Jenkinsfile now leaves `IMAGE_TAG` blank by default and automatically deploys:

```text
build-<Jenkins build number>
```

That way, the previous ECS task definition points to the previous image. Avoid using `latest` for rollback demos, because `latest` can be overwritten by the failed release.

## Audit check

```bash
AWS_PAGER="" aws dynamodb scan \
  --table-name AgentAudit \
  --region us-east-1 \
  --projection-expression "eventTime,eventType,#s,message" \
  --expression-attribute-names '{"#s":"status"}' \
  --query 'Items[*].[eventTime.S,eventType.S,status.S,message.S]' \
  --output table
```

## ECS check

```bash
aws ecs describe-services \
  --cluster dynamodb-demo-cluster \
  --services dynamodb-demo-service \
  --region us-east-1 \
  --query 'services[0].{status:status,running:runningCount,pending:pendingCount,desired:desiredCount,taskDefinition:taskDefinition,rollout:deployments[0].rolloutState}' \
  --output table
```
