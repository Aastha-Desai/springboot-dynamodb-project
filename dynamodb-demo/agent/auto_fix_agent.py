#!/usr/bin/env python3
"""
Auto-fix agent for known Spring Boot/DynamoDB demo bugs.

Part 5 scope:
- Detect the employeeId validation bug from the assignment use case.
- Fix the Java model automatically when validation is missing or incorrect.
- Run tests after a fix.
- Notify through console output and audit the action in DynamoDB.
"""

import argparse
import datetime as dt
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request
from typing import Any


LONG_EMPLOYEE_ID = "EMP-" + "X" * 25


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def audit_id(event_time: str, event_type: str, message: str) -> str:
    raw = f"{event_time}|{event_type}|{message}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:32]


def run_command(command: list[str], cwd: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)


def run_aws(args: list[str], region: str) -> Any:
    command = ["aws", *args, "--region", region]
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return json.loads(result.stdout or "{}")


def dynamodb_value(value: Any) -> dict[str, str]:
    if isinstance(value, int):
        return {"N": str(value)}
    return {"S": str(value)}


def put_audit_record(
    region: str,
    audit_table: str,
    event_type: str,
    status: str,
    message: str,
    source: str = "auto-fix-agent",
    bug_type: str = "VALIDATION",
    severity: str = "MEDIUM",
) -> None:
    event_time = utc_now()
    record = {
        "auditId": audit_id(event_time, event_type, message),
        "eventTime": event_time,
        "source": source,
        "eventType": event_type,
        "bugType": bug_type,
        "severity": severity,
        "status": status,
        "resource": "src/main/java/com/example/dynamodb_demo/model/Employee.java",
        "message": message[:900],
    }
    item = {key: dynamodb_value(value) for key, value in record.items()}
    run_aws(
        [
            "dynamodb",
            "put-item",
            "--table-name",
            audit_table,
            "--item",
            json.dumps(item),
        ],
        region,
    )


def deployed_validation_status(base_url: str) -> int:
    payload = json.dumps(
        {
            "employeeId": LONG_EMPLOYEE_ID,
            "name": "Validation Check",
            "department": "Quality",
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/employees",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code


def employee_model_path(project_dir: pathlib.Path) -> pathlib.Path:
    return project_dir / "src/main/java/com/example/dynamodb_demo/model/Employee.java"


def find_employee_id_line(lines: list[str]) -> int:
    for index, line in enumerate(lines):
        if re.search(r"\bprivate\s+String\s+employeeId\s*;", line):
            return index
    raise RuntimeError("Could not find employeeId field in Employee.java")


def has_correct_employee_id_size(lines: list[str], field_index: int) -> bool:
    annotation_block = lines[max(0, field_index - 8) : field_index]
    return any("@Size(max = 20)" in line or "@Size(max=20)" in line for line in annotation_block)


def ensure_employee_id_validation(project_dir: pathlib.Path, apply_fix: bool) -> tuple[bool, str]:
    path = employee_model_path(project_dir)
    text = path.read_text()
    lines = text.splitlines()
    field_index = find_employee_id_line(lines)

    if has_correct_employee_id_size(lines, field_index):
        return False, "employeeId already has @Size(max = 20); no source fix needed."

    changed = False

    if "import jakarta.validation.constraints.Size;" not in text:
        import_index = 0
        for index, line in enumerate(lines):
            if line.startswith("import "):
                import_index = index
                if line == "import jakarta.validation.constraints.NotBlank;":
                    break
        lines.insert(import_index + 1, "import jakarta.validation.constraints.Size;")
        changed = True
        field_index += 1

    annotation_start = field_index
    while annotation_start > 0 and lines[annotation_start - 1].strip().startswith("@"):
        annotation_start -= 1

    replaced_existing_size = False
    for index in range(annotation_start, field_index):
        if lines[index].strip().startswith("@Size"):
            lines[index] = "    @Size(max = 20)"
            replaced_existing_size = True
            changed = True
            break

    if not replaced_existing_size:
        insert_index = annotation_start
        for index in range(annotation_start, field_index):
            if lines[index].strip().startswith("@NotBlank"):
                insert_index = index + 1
                break
        lines.insert(insert_index, "    @Size(max = 20)")
        changed = True

    if not changed:
        return False, "No source change was required."

    if apply_fix:
        path.write_text("\n".join(lines) + "\n")
        return True, "Applied automatic fix: added @Size(max = 20) validation to employeeId."

    return False, "Validation fix is needed, but --no-apply was used; no file changed."


def run_tests(project_dir: pathlib.Path) -> tuple[bool, str]:
    result = run_command(["./mvnw", "test", "-q"], project_dir)
    if result.returncode == 0:
        return True, "Tests passed after auto-fix."
    output = (result.stdout + "\n" + result.stderr).strip()
    return False, f"Tests failed after auto-fix: {output[-900:]}"


def main(args: argparse.Namespace) -> int:
    project_dir = pathlib.Path(args.project_dir).resolve()
    print(f"[{utc_now()}] Auto-fix agent checking employeeId validation")

    deployed_status = None
    if args.base_url:
        deployed_status = deployed_validation_status(args.base_url)
        print(f"Deployed validation check returned HTTP {deployed_status}")
        if deployed_status != 400:
            message = f"Bug detected: employeeId longer than 20 chars returned HTTP {deployed_status}; expected 400."
            print(f"Notification: {message}")
            if not args.dry_run:
                put_audit_record(args.region, args.audit_table, "BUG_DETECTED", "OPEN", message, severity="HIGH")

    changed, fix_message = ensure_employee_id_validation(project_dir, apply_fix=not args.no_apply and not args.dry_run)
    print(f"Notification: {fix_message}")

    if args.dry_run:
        return 0

    if changed:
        put_audit_record(args.region, args.audit_table, "BUG_FIXED_AUTOMATICALLY", "FIX_APPLIED", fix_message)
        if args.run_tests:
            passed, test_message = run_tests(project_dir)
            print(f"Notification: {test_message}")
            put_audit_record(
                args.region,
                args.audit_table,
                "AUTO_FIX_VALIDATED" if passed else "AUTO_FIX_VALIDATION_FAILED",
                "TEST_PASSED" if passed else "TEST_FAILED",
                test_message,
                severity="MEDIUM" if passed else "HIGH",
            )
            return 0 if passed else 1
        return 0

    if deployed_status == 400 or deployed_status is None:
        put_audit_record(args.region, args.audit_table, "FIX_NOT_REQUIRED", "NO_ACTION", fix_message, severity="LOW")
        return 0

    message = "Source already contains validation fix; redeploy may be needed for the running ECS task."
    print(f"Notification: {message}")
    put_audit_record(args.region, args.audit_table, "FIX_NOT_APPLIED", "REDEPLOY_RECOMMENDED", message, severity="HIGH")
    return 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Automatically fix known validation bugs.")
    parser.add_argument("--project-dir", default=".", help="Spring Boot project directory.")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--audit-table", default="AgentAudit")
    parser.add_argument("--base-url", default="", help="Optional deployed app URL for validation smoke test.")
    parser.add_argument("--no-apply", action="store_true", help="Detect and report without modifying files.")
    parser.add_argument("--dry-run", action="store_true", help="Print actions without writing files or audit records.")
    parser.add_argument("--run-tests", action="store_true", default=True)
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(main(parse_args()))
    except Exception as exc:
        print(f"Auto-fix agent failed: {exc}", file=sys.stderr)
        sys.exit(2)
