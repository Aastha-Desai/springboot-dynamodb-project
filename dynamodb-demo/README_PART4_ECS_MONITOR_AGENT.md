# Part 4 - ECS Monitor Agent

This part adds a lightweight agent that monitors the ECS service and tracks bugs in DynamoDB.

## What the agent does

- Reads ECS service health, deployments, service events, running tasks, and recently stopped tasks.
- Detects likely bugs such as failed deployments, stopped tasks, failed containers, bad image pulls, unhealthy tasks, and capacity mismatches.
- Writes findings to the DynamoDB table `AgentAudit`.
- Deduplicates findings with a deterministic `auditId` so the same ECS event is not written repeatedly.

## Audit table

Table name: `AgentAudit`

Partition key:

```text
auditId String
```

Each bug/audit record includes:

- `eventTime`
- `clusterName`
- `serviceName`
- `source`
- `eventType`
- `bugType`
- `severity`
- `status`
- `resource`
- `message`

## Run once from CLI

```bash
python3 agent/ecs_monitor_agent.py \
  --region us-east-1 \
  --cluster dynamodb-demo-cluster \
  --service dynamodb-demo-service \
  --audit-table AgentAudit \
  --once
```

## Run continuously

```bash
python3 agent/ecs_monitor_agent.py \
  --region us-east-1 \
  --cluster dynamodb-demo-cluster \
  --service dynamodb-demo-service \
  --audit-table AgentAudit \
  --interval-seconds 60
```

## Dry run

```bash
python3 agent/ecs_monitor_agent.py --once --dry-run
```

## Jenkins usage

After the ECS deployment stage, Jenkins can run:

```bash
python3 agent/ecs_monitor_agent.py --once
```

Use `--fail-on-bug` later if you want the pipeline to fail when the agent finds a bug.
