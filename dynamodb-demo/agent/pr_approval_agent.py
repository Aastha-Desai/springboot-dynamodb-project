#!/usr/bin/env python3
"""
Create a pull request for an automatic fix and notify a human approver.

The agent is intentionally CI-friendly:
- Uses git for commit/push.
- Uses GitHub REST API when GITHUB_TOKEN is available.
- Sends email when SMTP_* and APPROVAL_EMAIL_TO are configured.
- Writes an email preview file when SMTP is not configured.
- Audits every action to DynamoDB AgentAudit.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import smtplib
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from email.message import EmailMessage
from typing import Any


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def stable_id(event_time: str, event_type: str, message: str) -> str:
    raw = f"{event_time}|{event_type}|{message}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:32]


def run(command: list[str], cwd: pathlib.Path, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"{' '.join(command)} failed: {detail}")
    return result


def run_aws(args: list[str], region: str) -> Any:
    result = subprocess.run(["aws", *args, "--region", region], text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return json.loads(result.stdout or "{}")


def dynamodb_value(value: Any) -> dict[str, str]:
    if isinstance(value, int):
        return {"N": str(value)}
    return {"S": str(value)}


def audit(
    region: str,
    table: str,
    event_type: str,
    status: str,
    message: str,
    resource: str = "github-pr",
    severity: str = "MEDIUM",
    dry_run: bool = False,
) -> None:
    event_time = utc_now()
    record = {
        "auditId": stable_id(event_time, event_type, message),
        "eventTime": event_time,
        "source": "pr-approval-agent",
        "eventType": event_type,
        "bugType": "HUMAN_APPROVAL",
        "severity": severity,
        "status": status,
        "resource": resource,
        "message": message[:900],
    }
    if dry_run:
        print(f"Audit dry-run: {event_type} {status} - {message}")
        return
    item = {key: dynamodb_value(value) for key, value in record.items()}
    run_aws(["dynamodb", "put-item", "--table-name", table, "--item", json.dumps(item)], region)


def current_branch(repo_dir: pathlib.Path) -> str:
    return run(["git", "branch", "--show-current"], repo_dir).stdout.strip()


def has_changes(repo_dir: pathlib.Path, paths: list[str] | None = None) -> bool:
    command = ["git", "status", "--porcelain"]
    if paths:
        command.extend(["--", *paths])
    return bool(run(command, repo_dir).stdout.strip())


def ensure_branch(repo_dir: pathlib.Path, branch: str, dry_run: bool) -> str:
    existing = current_branch(repo_dir)
    if existing == branch:
        return branch
    if dry_run:
        print(f"Dry-run: would create/switch to branch {branch}")
        return branch
    branches = run(["git", "branch", "--list", branch], repo_dir).stdout.strip()
    if branches:
        run(["git", "switch", branch], repo_dir)
    else:
        run(["git", "switch", "-c", branch], repo_dir)
    return branch


def commit_changes(repo_dir: pathlib.Path, paths: list[str], message: str, dry_run: bool) -> bool:
    if not has_changes(repo_dir, paths):
        print("No local changes to commit for the requested path set.")
        return False
    if dry_run:
        print(f"Dry-run: would stage {paths or ['all changes']} and commit: {message}")
        return True
    if paths:
        run(["git", "add", *paths], repo_dir)
    else:
        run(["git", "add", "-A"], repo_dir)
    staged = run(["git", "diff", "--cached", "--name-only"], repo_dir).stdout.strip()
    if not staged:
        print("No staged changes to commit.")
        return False
    run(["git", "commit", "-m", message], repo_dir)
    return True


def push_branch(repo_dir: pathlib.Path, remote: str, branch: str, dry_run: bool) -> None:
    if dry_run:
        print(f"Dry-run: would push {branch} to {remote}")
        return
    run(["git", "push", "-u", remote, branch], repo_dir)


def parse_github_remote(remote_url: str) -> tuple[str, str]:
    patterns = [
        r"github\.com[:/](?P<owner>[^/]+)/(?P<repo>[^/.]+)(?:\.git)?$",
        r"github\.com/(?P<owner>[^/]+)/(?P<repo>[^/.]+)(?:\.git)?$",
    ]
    for pattern in patterns:
        match = re.search(pattern, remote_url)
        if match:
            return match.group("owner"), match.group("repo")
    raise RuntimeError(f"Could not parse GitHub owner/repo from remote URL: {remote_url}")


def remote_url(repo_dir: pathlib.Path, remote: str) -> str:
    return run(["git", "remote", "get-url", remote], repo_dir).stdout.strip()


def create_github_pr(
    owner: str,
    repo: str,
    head: str,
    base: str,
    title: str,
    body: str,
    token: str,
) -> str:
    payload = json.dumps({"title": title, "head": head, "base": base, "body": body}).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{owner}/{repo}/pulls",
        data=payload,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "springboot-dynamodb-pr-approval-agent",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            data = json.loads(response.read().decode("utf-8"))
            return data["html_url"]
    except urllib.error.HTTPError as exc:
        body_text = exc.read().decode("utf-8", errors="replace")
        if exc.code == 422 and "pull request already exists" in body_text.lower():
            return find_existing_pr(owner, repo, head, base, token)
        raise RuntimeError(f"GitHub PR creation failed HTTP {exc.code}: {body_text}")


def find_existing_pr(owner: str, repo: str, head: str, base: str, token: str) -> str:
    query = urllib.parse.urlencode({"head": f"{owner}:{head}", "base": base, "state": "open"})
    request = urllib.request.Request(
        f"https://api.github.com/repos/{owner}/{repo}/pulls?{query}",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "springboot-dynamodb-pr-approval-agent",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        data = json.loads(response.read().decode("utf-8"))
        if not data:
            raise RuntimeError("GitHub reported an existing PR but it could not be found.")
        return data[0]["html_url"]


def pr_fallback_url(owner: str, repo: str, branch: str) -> str:
    return f"https://github.com/{owner}/{repo}/pull/new/{branch}"


def email_body(project: str, pr_url: str, summary: str) -> str:
    return (
        f"Human approval requested for {project}.\n\n"
        f"Summary:\n{summary}\n\n"
        f"Review PR:\n{pr_url}\n\n"
        "Approve by merging the pull request. Deployment to ECS should proceed after approval.\n"
    )


def send_email(subject: str, body: str, dry_run: bool, outbox: pathlib.Path) -> bool:
    to_address = os.getenv("APPROVAL_EMAIL_TO", "").strip()
    from_address = os.getenv("EMAIL_FROM", os.getenv("SMTP_USERNAME", "")).strip()
    smtp_host = os.getenv("SMTP_HOST", "").strip()
    smtp_port = int(os.getenv("SMTP_PORT", "587"))
    smtp_username = os.getenv("SMTP_USERNAME", "").strip()
    smtp_password = os.getenv("SMTP_PASSWORD", "")

    if dry_run or not (to_address and from_address and smtp_host):
        outbox.parent.mkdir(parents=True, exist_ok=True)
        outbox.write_text(f"To: {to_address or '<APPROVAL_EMAIL_TO>'}\nSubject: {subject}\n\n{body}")
        print(f"Email notification preview written to {outbox}")
        return False

    message = EmailMessage()
    message["To"] = to_address
    message["From"] = from_address
    message["Subject"] = subject
    message.set_content(body)

    context = ssl.create_default_context()
    with smtplib.SMTP(smtp_host, smtp_port, timeout=30) as smtp:
        smtp.starttls(context=context)
        if smtp_username:
            smtp.login(smtp_username, smtp_password)
        smtp.send_message(message)
    print(f"Email notification sent to {to_address}")
    return True


def main(args: argparse.Namespace) -> int:
    repo_dir = pathlib.Path(args.repo_dir).resolve()
    branch = args.branch or f"agent-fix-{dt.datetime.now(dt.UTC).strftime('%Y%m%d%H%M%S')}"
    print(f"[{utc_now()}] PR approval agent preparing branch {branch}")

    ensure_branch(repo_dir, branch, args.dry_run)
    committed = commit_changes(repo_dir, args.paths, args.commit_message, args.dry_run)
    push_branch(repo_dir, args.remote, branch, args.dry_run)

    owner, repo = parse_github_remote(remote_url(repo_dir, args.remote))
    token = os.getenv("GITHUB_TOKEN", os.getenv("GH_TOKEN", "")).strip()
    pr_url = ""
    if args.dry_run:
        pr_url = pr_fallback_url(owner, repo, branch)
        print(f"Dry-run: would create PR {branch} -> {args.base_branch}: {pr_url}")
    elif token:
        pr_url = create_github_pr(owner, repo, branch, args.base_branch, args.pr_title, args.pr_body, token)
        print(f"Pull request ready: {pr_url}")
        audit(args.region, args.audit_table, "PR_CREATED", "WAITING_FOR_HUMAN_APPROVAL", pr_url, pr_url)
    else:
        pr_url = pr_fallback_url(owner, repo, branch)
        print("GITHUB_TOKEN/GH_TOKEN is not set, so the agent could not create the PR through the API.")
        print(f"Manual PR URL: {pr_url}")
        audit(
            args.region,
            args.audit_table,
            "PR_CREATION_NEEDS_TOKEN",
            "ACTION_REQUIRED",
            f"Set GITHUB_TOKEN/GH_TOKEN or create PR manually: {pr_url}",
            pr_url,
            severity="HIGH",
            dry_run=args.dry_run,
        )

    body = email_body(args.project_name, pr_url, args.summary)
    email_sent = send_email(args.email_subject, body, args.dry_run, repo_dir / args.outbox_file)
    audit(
        args.region,
        args.audit_table,
        "EMAIL_NOTIFICATION_SENT" if email_sent else "EMAIL_NOTIFICATION_PREPARED",
        "SENT" if email_sent else "SMTP_CONFIGURATION_REQUIRED",
        f"Human approval notification {'sent' if email_sent else 'prepared'} for {pr_url}",
        pr_url,
        dry_run=args.dry_run,
    )

    if committed:
        audit(
            args.region,
            args.audit_table,
            "FIX_COMMITTED_FOR_APPROVAL",
            "WAITING_FOR_HUMAN_APPROVAL",
            f"Committed fix branch {branch}",
            branch,
            dry_run=args.dry_run,
        )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Raise PR and notify human approval for agent fixes.")
    parser.add_argument("--repo-dir", default="..", help="Git repository root when running from dynamodb-demo.")
    parser.add_argument("--branch", default="", help="Fix branch name. Defaults to agent-fix timestamp.")
    parser.add_argument("--base-branch", default="main")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--paths", nargs="*", default=[], help="Paths to stage. Defaults to all changes.")
    parser.add_argument("--commit-message", default="fix: apply agent-generated bug fix")
    parser.add_argument("--pr-title", default="Agent generated bug fix")
    parser.add_argument("--pr-body", default="This PR was generated by the auto-fix agent and is waiting for human approval.")
    parser.add_argument("--project-name", default="springboot-dynamodb-project")
    parser.add_argument("--summary", default="The agent generated a bug fix and requests human approval.")
    parser.add_argument("--email-subject", default="Human approval requested for agent bug fix")
    parser.add_argument("--outbox-file", default="build/approval-email.txt")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--audit-table", default="AgentAudit")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(main(parse_args()))
    except Exception as exc:
        print(f"PR approval agent failed: {exc}", file=sys.stderr)
        sys.exit(2)
