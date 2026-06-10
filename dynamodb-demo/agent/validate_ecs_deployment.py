#!/usr/bin/env python3
"""
Validate an approved deployment running on AWS ECS.

Part 7 scope:
- Resolve the public URL for the running ECS task.
- Validate Swagger/OpenAPI.
- Validate the sunny-day employee POST/GET flow.
- Validate the exceptional employeeId > 20 rule returns HTTP 400.
- Audit validation success/failure to DynamoDB.
"""

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from typing import Any


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def stable_id(event_time: str, event_type: str, message: str) -> str:
    raw = f"{event_time}|{event_type}|{message}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:32]


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
    resource: str,
    severity: str = "MEDIUM",
    skip_audit: bool = False,
) -> None:
    if skip_audit:
        print(f"Audit skipped: {event_type} {status} - {message}")
        return
    event_time = utc_now()
    record = {
        "auditId": stable_id(event_time, event_type, message),
        "eventTime": event_time,
        "source": "deployment-validation-agent",
        "eventType": event_type,
        "bugType": "DEPLOYMENT_VALIDATION",
        "severity": severity,
        "status": status,
        "resource": resource,
        "message": message[:900],
    }
    item = {key: dynamodb_value(value) for key, value in record.items()}
    run_aws(["dynamodb", "put-item", "--table-name", table, "--item", json.dumps(item)], region)


def http_request(url: str, method: str = "GET", payload: dict[str, Any] | None = None) -> tuple[int, str]:
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")


def first_detail_value(details: list[dict[str, str]], name: str) -> str:
    for detail in details:
        if detail.get("name") == name:
            return detail.get("value", "")
    return ""


def public_base_url(region: str, cluster: str, service: str, port: int) -> str:
    listed = run_aws(
        ["ecs", "list-tasks", "--cluster", cluster, "--service-name", service, "--desired-status", "RUNNING"],
        region,
    )
    task_arns = listed.get("taskArns", [])
    if not task_arns:
        raise RuntimeError(f"No running ECS tasks found for {cluster}/{service}")

    described = run_aws(["ecs", "describe-tasks", "--cluster", cluster, "--tasks", task_arns[0]], region)
    task = described.get("tasks", [{}])[0]
    attachments = task.get("attachments", [])
    if not attachments:
        raise RuntimeError(f"Running task has no network attachment: {task_arns[0]}")

    eni_id = first_detail_value(attachments[0].get("details", []), "networkInterfaceId")
    if not eni_id:
        raise RuntimeError(f"Could not resolve networkInterfaceId for task: {task_arns[0]}")

    eni = run_aws(
        [
            "ec2",
            "describe-network-interfaces",
            "--network-interface-ids",
            eni_id,
        ],
        region,
    )
    network_interface = eni.get("NetworkInterfaces", [{}])[0]
    public_ip = network_interface.get("Association", {}).get("PublicIp")
    if not public_ip:
        raise RuntimeError(f"ECS task ENI {eni_id} does not have a public IP. Use --base-url with a load balancer URL.")
    return f"http://{public_ip}:{port}"


def validate(base_url: str) -> list[str]:
    failures: list[str] = []

    api_docs_status, api_docs_body = http_request(f"{base_url}/v3/api-docs")
    if api_docs_status != 200 or "Employee API" not in api_docs_body:
        failures.append(f"Swagger validation failed: HTTP {api_docs_status}")

    employee_id = f"EMP-{int(time.time())}"
    employee = {
        "employeeId": employee_id,
        "name": "Deployment Validator",
        "department": "Quality",
    }
    post_status, post_body = http_request(f"{base_url}/api/employees", method="POST", payload=employee)
    if post_status != 201:
        failures.append(f"Employee POST failed: HTTP {post_status} body={post_body[:200]}")

    get_status, get_body = http_request(f"{base_url}/api/employees/{employee_id}")
    if get_status != 200 or employee_id not in get_body:
        failures.append(f"Employee GET failed: HTTP {get_status} body={get_body[:200]}")

    invalid_employee = {
        "employeeId": "EMP-" + ("X" * 25),
        "name": "Invalid Employee",
        "department": "Quality",
    }
    invalid_status, invalid_body = http_request(f"{base_url}/api/employees", method="POST", payload=invalid_employee)
    if invalid_status != 400:
        failures.append(f"Long employeeId validation failed: expected HTTP 400, got {invalid_status} body={invalid_body[:200]}")

    return failures


def main(args: argparse.Namespace) -> int:
    base_url = args.base_url.rstrip("/") if args.base_url else public_base_url(
        args.region,
        args.cluster,
        args.service,
        args.port,
    )
    print(f"[{utc_now()}] Validating ECS deployment at {base_url}")
    failures = validate(base_url)
    if failures:
        message = "Deployment validation failed: " + " | ".join(failures)
        print(message)
        audit(args.region, args.audit_table, "DEPLOYMENT_VALIDATION_FAILED", "FAILED", message, base_url, "HIGH", args.skip_audit)
        return 1

    message = "Deployment validation succeeded: Swagger, POST/GET employee, and long employeeId validation passed."
    print(message)
    audit(args.region, args.audit_table, "DEPLOYMENT_VALIDATED", "PASSED", message, base_url, "LOW", args.skip_audit)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate approved ECS deployment.")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--cluster", default="dynamodb-demo-cluster")
    parser.add_argument("--service", default="dynamodb-demo-service")
    parser.add_argument("--audit-table", default="AgentAudit")
    parser.add_argument("--base-url", default="", help="Optional explicit app URL. If omitted, resolve ECS task public IP.")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--skip-audit", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(main(parse_args()))
    except Exception as exc:
        print(f"Deployment validation agent failed: {exc}", file=sys.stderr)
        sys.exit(2)
