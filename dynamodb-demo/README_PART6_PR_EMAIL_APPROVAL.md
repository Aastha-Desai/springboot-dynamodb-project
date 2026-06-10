# Part 6 - PR and Email Approval Agent

This part adds an agent that raises a pull request for an automatic fix and notifies a human approver.

## What it does

- Creates or switches to a fix branch.
- Stages and commits agent-generated changes.
- Pushes the branch to GitHub.
- Creates a GitHub pull request when `GITHUB_TOKEN` is configured.
- Sends an email notification when SMTP settings are configured.
- Writes approval events to `AgentAudit`.

## Required for real PR creation

Set a GitHub token with pull request permissions:

```bash
export GITHUB_TOKEN=<your-token>
```

`GH_TOKEN` also works. Without `GITHUB_TOKEN` or `GH_TOKEN`, the agent pushes the branch, prints a manual PR URL, and audits that token setup is required.

## Required for real email sending

Set these environment variables:

```bash
export APPROVAL_EMAIL_TO=approver@example.com
export EMAIL_FROM=your-email@example.com
export SMTP_HOST=smtp.example.com
export SMTP_PORT=587
export SMTP_USERNAME=your-email@example.com
export SMTP_PASSWORD=<app-password>
```

Without SMTP settings, the agent writes an approval email preview to:

```text
build/approval-email.txt
```

That preview is enough for a local demo, but a real email requires the SMTP variables above.

## End-to-end test with a simulated bug

1. Temporarily remove this annotation from `Employee.java`:

```java
@Size(max = 20)
```

2. Run Part 5 to fix it automatically:

```bash
python3 agent/auto_fix_agent.py
```

3. Run Part 6 to branch, commit, push, create/prepare the PR, and prepare/send the email:

```bash
python3 agent/pr_approval_agent.py \
  --repo-dir .. \
  --branch agent-validation-fix \
  --paths dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java \
  --commit-message "fix: restore employee id validation" \
  --pr-title "Agent fix: restore employeeId validation" \
  --summary "The auto-fix agent restored @Size(max = 20) validation for employeeId."
```

If `GITHUB_TOKEN` and SMTP settings are configured, this creates a real PR and sends a real email.
If they are not configured, it still pushes the branch, prints the GitHub PR URL, writes the email preview, and audits both actions.

## Dry run

From `dynamodb-demo`:

```bash
python3 agent/pr_approval_agent.py \
  --repo-dir .. \
  --branch agent-validation-fix-demo \
  --paths dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java \
  --dry-run
```

## Real run after an auto-fix

From `dynamodb-demo`:

```bash
python3 agent/pr_approval_agent.py \
  --repo-dir .. \
  --branch agent-validation-fix \
  --paths dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java \
  --commit-message "fix: restore employee id validation" \
  --pr-title "Agent fix: restore employeeId validation" \
  --summary "The auto-fix agent restored @Size(max = 20) validation for employeeId."
```

The PR should then be reviewed and merged by a human before production deployment.
