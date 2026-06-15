#!/usr/bin/env python3
"""
Run the agent workflow as one coordinated automation.

Flow:
1. Monitor ECS once and audit findings.
2. Run the auto-fix agent.
3. If source files changed, raise a PR and notify a human approver.

Secrets are intentionally not stored in this file. The PR/email agent reads
GITHUB_TOKEN/GH_TOKEN and SMTP/approval email settings from the environment.
"""

import argparse
import datetime as dt
import json
import os
import pathlib
import subprocess
import sys
from typing import Any

from validate_ecs_deployment import public_base_url


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def run(command: list[str], cwd: pathlib.Path, check: bool = True) -> subprocess.CompletedProcess[str]:
    print(f"+ {' '.join(command)}")
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    if result.stdout:
        print(result.stdout.rstrip())
    if result.stderr:
        print(result.stderr.rstrip(), file=sys.stderr)
    if check and result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed with exit code {result.returncode}")
    return result


def load_config(path: str) -> dict[str, Any]:
    if not path:
        return {}
    config_path = pathlib.Path(path).expanduser().resolve()
    with config_path.open() as file:
        return {key.replace("-", "_"): value for key, value in json.load(file).items()}


def git_has_changes(repo_dir: pathlib.Path, paths: list[str]) -> bool:
    command = ["git", "status", "--porcelain"]
    if paths:
        command.extend(["--", *paths])
    return bool(run(command, repo_dir, check=True).stdout.strip())


def env_is_ready_for_real_approval(email_mode: str) -> tuple[bool, list[str]]:
    missing: list[str] = []
    if not (os.getenv("GITHUB_TOKEN") or os.getenv("GH_TOKEN")):
        missing.append("GITHUB_TOKEN or GH_TOKEN")

    if email_mode == "smtp":
        required = ["APPROVAL_EMAIL_TO", "SMTP_HOST", "SMTP_USERNAME", "SMTP_PASSWORD"]
        for name in required:
            if not os.getenv(name):
                missing.append(name)
        if not (os.getenv("EMAIL_FROM") or os.getenv("SMTP_USERNAME")):
            missing.append("EMAIL_FROM or SMTP_USERNAME")
    return not missing, missing


def resolve_base_url(args: argparse.Namespace) -> str:
    if args.base_url:
        return args.base_url
    if not args.resolve_ecs_url:
        return ""
    return public_base_url(args.region, args.cluster, args.service, args.port)


def run_monitor(args: argparse.Namespace, project_dir: pathlib.Path) -> bool:
    if args.skip_monitor:
        print("ECS monitor skipped.")
        return False

    command = [
        sys.executable,
        "agent/ecs_monitor_agent.py",
        "--region",
        args.region,
        "--cluster",
        args.cluster,
        "--service",
        args.service,
        "--audit-table",
        args.audit_table,
        "--once",
        "--fail-on-bug",
    ]
    if args.dry_run:
        command.append("--dry-run")

    result = run(command, project_dir, check=False)
    if result.returncode == 0:
        return False
    if result.returncode == 1:
        print("ECS monitor found at least one finding.")
        return True
    raise RuntimeError(f"ECS monitor failed with exit code {result.returncode}")


def run_auto_fix(args: argparse.Namespace, project_dir: pathlib.Path, base_url: str) -> None:
    command = [
        sys.executable,
        "agent/auto_fix_agent.py",
        "--project-dir",
        str(project_dir),
        "--region",
        args.region,
        "--audit-table",
        args.audit_table,
    ]
    if base_url:
        command.extend(["--base-url", base_url])
    if args.dry_run:
        command.append("--dry-run")

    run(command, project_dir, check=False)


def run_pr_approval(args: argparse.Namespace, project_dir: pathlib.Path) -> None:
    command = [
        sys.executable,
        "agent/pr_approval_agent.py",
        "--repo-dir",
        str(args.repo_dir),
        "--base-branch",
        args.base_branch,
        "--remote",
        args.remote,
        "--branch",
        args.fix_branch,
        "--commit-message",
        args.commit_message,
        "--pr-title",
        args.pr_title,
        "--pr-body",
        args.pr_body,
        "--project-name",
        args.project_name,
        "--summary",
        args.summary,
        "--email-subject",
        args.email_subject,
        "--email-mode",
        args.email_mode,
        "--outbox-file",
        args.outbox_file,
        "--region",
        args.region,
        "--audit-table",
        args.audit_table,
        "--paths",
        *args.paths,
    ]
    if args.github_repo:
        command.extend(["--github-repo", args.github_repo])
    if args.dry_run:
        command.append("--dry-run")

    run(command, project_dir)


def main(args: argparse.Namespace) -> int:
    project_dir = pathlib.Path(args.project_dir).expanduser().resolve()
    repo_dir = pathlib.Path(args.repo_dir).expanduser().resolve()
    args.repo_dir = repo_dir

    print(f"[{utc_now()}] Starting coordinated agent workflow")
    ready, missing = env_is_ready_for_real_approval(args.email_mode)
    if not ready:
        print("Real PR/email approval is not fully configured.")
        print("Missing: " + ", ".join(missing))
        print("The workflow can still create previews/manual PR instructions unless --email-mode smtp requires email.")

    monitor_found_bug = run_monitor(args, project_dir)
    base_url = resolve_base_url(args)
    if base_url:
        print(f"Using deployed app URL for validation smoke check: {base_url}")

    run_auto_fix(args, project_dir, base_url)

    if not git_has_changes(repo_dir, args.paths):
        if monitor_found_bug:
            print("Monitor found a bug, but no configured source files changed. No PR was created.")
            return 1
        print("No source changes were produced. No PR/email approval is needed.")
        return 0

    print("Source changes detected. Creating approval PR and notification.")
    run_pr_approval(args, project_dir)
    print("Agent workflow completed. Human approval is now required before deployment.")
    return 0


def parse_args() -> argparse.Namespace:
    pre_parser = argparse.ArgumentParser(add_help=False)
    pre_parser.add_argument("--config", default="", help="Optional JSON config file.")
    pre_args, remaining = pre_parser.parse_known_args()
    config = load_config(pre_args.config)

    parser = argparse.ArgumentParser(description="Coordinate monitor, auto-fix, PR, and email agents.")
    parser.add_argument("--config", default=pre_args.config)
    parser.add_argument("--project-dir", default=".", help="Spring Boot project directory.")
    parser.add_argument("--repo-dir", default="..", help="Git repository root.")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--cluster", default="dynamodb-demo-cluster")
    parser.add_argument("--service", default="dynamodb-demo-service")
    parser.add_argument("--audit-table", default="AgentAudit")
    parser.add_argument("--base-url", default="", help="Optional deployed app URL.")
    parser.add_argument("--resolve-ecs-url", action="store_true", default=True)
    parser.add_argument("--no-resolve-ecs-url", action="store_false", dest="resolve_ecs_url")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--skip-monitor", action="store_true")
    parser.add_argument("--paths", nargs="*", default=["dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java"])
    parser.add_argument("--base-branch", default="main")
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--github-repo", default="")
    parser.add_argument("--fix-branch", default="agent-auto-fix")
    parser.add_argument("--commit-message", default="fix: apply agent-generated bug fix")
    parser.add_argument("--pr-title", default="Agent generated bug fix")
    parser.add_argument("--pr-body", default="This PR was generated by the coordinated agent workflow and is waiting for human approval.")
    parser.add_argument("--project-name", default="springboot-dynamodb-project")
    parser.add_argument("--summary", default="The monitor/auto-fix workflow generated a bug fix and requests human approval.")
    parser.add_argument("--email-subject", default="Human approval requested for agent bug fix")
    parser.add_argument("--email-mode", choices=("auto", "smtp", "preview"), default="auto")
    parser.add_argument("--outbox-file", default="build/approval-email.txt")
    parser.add_argument("--dry-run", action="store_true")
    parser.set_defaults(**config)
    return parser.parse_args(remaining)


if __name__ == "__main__":
    try:
        sys.exit(main(parse_args()))
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as exc:
        print(f"Orchestrator failed: {exc}", file=sys.stderr)
        sys.exit(2)
