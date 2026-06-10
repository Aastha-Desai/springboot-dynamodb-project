# Part 5 - Auto-Fix Agent

This part adds an agent that can fix a known bug automatically and notify/audit the action taken.

## Current supported auto-fix

Assignment use case:

```text
employeeId > 20 chars should be rejected by validation.
```

The agent checks `Employee.java` and ensures the `employeeId` field has:

```java
@Size(max = 20)
```

If the validation is missing or incorrect, the agent:

1. Updates the Java model.
2. Runs Maven tests.
3. Prints a notification of the action taken.
4. Writes audit records to the DynamoDB `AgentAudit` table.

## Run source check and auto-fix

```bash
python3 agent/auto_fix_agent.py
```

## Run against deployed ECS app too

```bash
python3 agent/auto_fix_agent.py \
  --base-url http://18.206.91.114:8080
```

## Dry run

```bash
python3 agent/auto_fix_agent.py --dry-run
```

## What to expect today

The project already has the correct validation, so the agent should notify:

```text
employeeId already has @Size(max = 20); no source fix needed.
```

That still proves the agent can inspect the code and decide that no automatic fix is necessary.
