#!/usr/bin/env python3
"""
Monitor an ECS service for deployment/task bugs and audit findings to DynamoDB.

This intentionally uses the AWS CLI instead of boto3 so it can run anywhere the
current Jenkins deployment already works.
"""

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
import time
from typing import Any


BUG_WORDS = (
    "cannotpull",
    "error",
    "failed",
    "unable",
    "unhealthy",
)

ERROR_STOP_WORDS = (
    "cannot",
    "error",
    "essential container",
    "failed",
    "outofmemory",
    "task failed",
    "unhealthy",
)


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def run_aws(args: list[str], region: str, expect_json: bool = True) -> Any:
    command = ["aws", *args, "--region", region]
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    if not expect_json:
        return result.stdout.strip()
    return json.loads(result.stdout or "{}")


def bug_id(cluster: str, service: str, source: str, message: str, resource: str) -> str:
    raw = f"{cluster}|{service}|{source}|{message}|{resource}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()[:32]


def dynamodb_value(value: Any) -> dict[str, str]:
    if isinstance(value, int):
        return {"N": str(value)}
    return {"S": str(value)}


def put_audit_record(region: str, table: str, record: dict[str, Any]) -> bool:
    item = {key: dynamodb_value(value) for key, value in record.items()}
    command = [
        "dynamodb",
        "put-item",
        "--table-name",
        table,
        "--item",
        json.dumps(item),
        "--condition-expression",
        "attribute_not_exists(auditId)",
    ]
    try:
        run_aws(command, region, expect_json=True)
        return True
    except RuntimeError as exc:
        if "ConditionalCheckFailedException" in str(exc):
            return False
        raise


def make_record(
    cluster: str,
    service: str,
    source: str,
    bug_type: str,
    severity: str,
    message: str,
    resource: str,
) -> dict[str, Any]:
    now = utc_now()
    return {
        "auditId": bug_id(cluster, service, source, message, resource),
        "eventTime": now,
        "clusterName": cluster,
        "serviceName": service,
        "source": source,
        "eventType": "BUG_DETECTED",
        "bugType": bug_type,
        "severity": severity,
        "status": "OPEN",
        "resource": resource,
        "message": message[:900],
    }


def describe_service(region: str, cluster: str, service: str) -> dict[str, Any]:
    response = run_aws(
        ["ecs", "describe-services", "--cluster", cluster, "--services", service],
        region,
    )
    failures = response.get("failures", [])
    if failures:
        raise RuntimeError(f"ECS describe-services failure: {failures}")
    services = response.get("services", [])
    if not services:
        raise RuntimeError(f"ECS service not found: {cluster}/{service}")
    return services[0]


def describe_tasks(
    region: str,
    cluster: str,
    service: str,
    desired_status: str,
    max_tasks: int,
) -> list[dict[str, Any]]:
    listed = run_aws(
        [
            "ecs",
            "list-tasks",
            "--cluster",
            cluster,
            "--service-name",
            service,
            "--desired-status",
            desired_status,
        ],
        region,
    )
    task_arns = listed.get("taskArns", [])[:max_tasks]
    if not task_arns:
        return []
    described = run_aws(
        ["ecs", "describe-tasks", "--cluster", cluster, "--tasks", *task_arns],
        region,
    )
    return described.get("tasks", [])


def collect_findings(
    region: str,
    cluster: str,
    service: str,
    event_limit: int,
    task_limit: int,
) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    service_doc = describe_service(region, cluster, service)

    desired = service_doc.get("desiredCount", 0)
    running = service_doc.get("runningCount", 0)
    pending = service_doc.get("pendingCount", 0)
    service_arn = service_doc.get("serviceArn", f"{cluster}/{service}")

    if running < desired:
        findings.append(
            make_record(
                cluster,
                service,
                "ecs-service",
                "SERVICE_CAPACITY",
                "HIGH",
                f"Running tasks below desired count: running={running}, desired={desired}, pending={pending}",
                service_arn,
            )
        )

    for deployment in service_doc.get("deployments", []):
        rollout = deployment.get("rolloutState")
        failed_tasks = deployment.get("failedTasks", 0)
        task_definition = deployment.get("taskDefinition", "unknown-task-definition")
        if rollout == "FAILED":
            findings.append(
                make_record(
                    cluster,
                    service,
                    "ecs-deployment",
                    "DEPLOYMENT_FAILED",
                    "CRITICAL",
                    deployment.get("rolloutStateReason", "ECS deployment failed"),
                    task_definition,
                )
            )
        if failed_tasks:
            findings.append(
                make_record(
                    cluster,
                    service,
                    "ecs-deployment",
                    "FAILED_TASKS",
                    "HIGH",
                    f"ECS deployment has failedTasks={failed_tasks}",
                    task_definition,
                )
            )

    for event in service_doc.get("events", [])[:event_limit]:
        message = event.get("message", "")
        lower_message = message.lower()
        if any(word in lower_message for word in BUG_WORDS):
            findings.append(
                make_record(
                    cluster,
                    service,
                    "ecs-service-event",
                    "ECS_EVENT",
                    "MEDIUM",
                    message,
                    service_arn,
                )
            )

    tasks = describe_tasks(region, cluster, service, "RUNNING", task_limit)
    tasks.extend(describe_tasks(region, cluster, service, "STOPPED", task_limit))
    for task in tasks:
        task_arn = task.get("taskArn", "unknown-task")
        stop_reason = task.get("stoppedReason", "")
        if stop_reason and any(word in stop_reason.lower() for word in ERROR_STOP_WORDS):
            findings.append(
                make_record(
                    cluster,
                    service,
                    "ecs-task",
                    "TASK_STOPPED",
                    "HIGH",
                    stop_reason,
                    task_arn,
                )
            )
        for container in task.get("containers", []):
            exit_code = container.get("exitCode")
            reason = container.get("reason", "")
            last_status = container.get("lastStatus", "")
            if exit_code not in (None, 0) or reason:
                findings.append(
                    make_record(
                        cluster,
                        service,
                        "ecs-container",
                        "CONTAINER_ERROR",
                        "HIGH",
                        f"Container {container.get('name', 'unknown')} status={last_status} exitCode={exit_code} reason={reason}",
                        task_arn,
                    )
                )

    return findings


def monitor(args: argparse.Namespace) -> int:
    while True:
        started = utc_now()
        print(f"[{started}] Monitoring ECS service {args.cluster}/{args.service}")
        findings = collect_findings(
            args.region,
            args.cluster,
            args.service,
            args.event_limit,
            args.task_limit,
        )

        inserted = 0
        for finding in findings:
            if args.dry_run:
                print(json.dumps(finding, indent=2))
                continue
            if put_audit_record(args.region, args.audit_table, finding):
                inserted += 1
                print(f"Recorded bug {finding['auditId']}: {finding['message']}")

        if not findings:
            print("No ECS bugs detected.")
        elif not args.dry_run:
            print(f"Detected {len(findings)} finding(s); inserted {inserted} new audit record(s).")

        if args.once:
            return 1 if findings and args.fail_on_bug else 0
        time.sleep(args.interval_seconds)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Monitor ECS tasks and audit bugs to DynamoDB.")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--cluster", default="dynamodb-demo-cluster")
    parser.add_argument("--service", default="dynamodb-demo-service")
    parser.add_argument("--audit-table", default="AgentAudit")
    parser.add_argument("--event-limit", type=int, default=10)
    parser.add_argument("--task-limit", type=int, default=10)
    parser.add_argument("--interval-seconds", type=int, default=60)
    parser.add_argument("--once", action="store_true", help="Run one monitor cycle and exit.")
    parser.add_argument("--dry-run", action="store_true", help="Print findings without writing DynamoDB.")
    parser.add_argument("--fail-on-bug", action="store_true", help="Exit 1 when findings exist.")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(monitor(parse_args()))
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as exc:
        print(f"Agent failed: {exc}", file=sys.stderr)
        sys.exit(2)
