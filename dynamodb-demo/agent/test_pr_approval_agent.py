#!/usr/bin/env python3
"""Local integration test for the PR approval agent."""

import pathlib
import shutil
import subprocess
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[2]
AGENT = ROOT / "dynamodb-demo/agent/pr_approval_agent.py"


def run(command: list[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}")
    return result


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pr-agent-test-") as tmp:
        base = pathlib.Path(tmp)
        remote = base / "remote.git"
        repo = base / "repo"

        run(["git", "init", "--bare", str(remote)], base)
        run(["git", "init", str(repo)], base)
        run(["git", "config", "user.email", "agent-test@example.com"], repo)
        run(["git", "config", "user.name", "Agent Test"], repo)
        run(["git", "remote", "add", "origin", str(remote)], repo)

        tracked_file = repo / "demo.txt"
        tracked_file.write_text("before\n")
        run(["git", "add", "demo.txt"], repo)
        run(["git", "commit", "-m", "initial"], repo)
        run(["git", "branch", "-M", "main"], repo)
        run(["git", "push", "-u", "origin", "main"], repo)

        shutil.copy2(AGENT, repo / "pr_approval_agent.py")
        tracked_file.write_text("after\n")

        result = run(
            [
                "python3",
                "pr_approval_agent.py",
                "--repo-dir",
                ".",
                "--remote",
                "origin",
                "--github-repo",
                "Aastha-Desai/springboot-dynamodb-project",
                "--branch",
                "agent-test-fix",
                "--paths",
                "demo.txt",
                "--commit-message",
                "fix: test agent approval flow",
                "--pr-title",
                "Agent test PR",
                "--summary",
                "Local integration test for branch, commit, push, and approval email preview.",
                "--email-mode",
                "preview",
                "--outbox-file",
                "build/approval-email.txt",
                "--skip-audit",
            ],
            repo,
        )
        assert "Manual PR URL" in result.stdout
        assert "Email notification preview written" in result.stdout
        assert (repo / "build/approval-email.txt").exists()
        pushed = run(["git", "ls-remote", "--heads", "origin", "agent-test-fix"], repo).stdout
        assert "refs/heads/agent-test-fix" in pushed
        print("PR approval agent local integration test passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
