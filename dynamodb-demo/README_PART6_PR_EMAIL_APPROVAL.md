# Part 6 - PR and Email Approval Agent

This part adds an agent that raises a pull request for an automatic fix and notifies a human approver.

## What it does

- Creates or switches to a fix branch.
- Stages and commits agent-generated changes.
- Pushes the branch to GitHub.
- Creates a GitHub pull request when `GITHUB_TOKEN` is configured.
- Sends an email notification when SMTP settings are configured.
- Writes approval events to `AgentAudit`.
- Accepts a JSON config file so the same agent can run against another repository.

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

The real assignment/demo should use SMTP mode:

```bash
--email-mode smtp
```

In `smtp` mode, the agent fails if email cannot be sent. Without SMTP settings in default `auto` mode, the agent writes an approval email preview to:

```text
build/approval-email.txt
```

That preview is enough for a local demo, but a real email requires the SMTP variables above.

## Reusable config file

Copy and edit:

```bash
cp agent/project-config.example.json agent/project-config.local.json
```

Then run the same agent against any Git repo:

```bash
python3 agent/pr_approval_agent.py --config agent/project-config.local.json
```

The target repo, GitHub repo, changed paths, branch, PR text, and email mode all come from the config file.

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
  --summary "The auto-fix agent restored @Size(max = 20) validation for employeeId." \
  --email-mode smtp
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

## Local integration test

This checks the generic branch/commit/approval-notification flow without using the real GitHub repo:

```bash
python3 agent/test_pr_approval_agent.py
```

This proves the agent can run against another repository shape because it creates a temporary Git repo, commits a generic file change, pushes a branch to a temporary bare remote, prepares a PR URL, and writes the approval email preview.

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
