package com.example.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.DesiredStatus;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.ServiceEvent;
import software.amazon.awssdk.services.ecs.model.Task;

public final class JavaEcsMonitorAgent {

    private static final List<String> BUG_WORDS = List.of("cannotpull", "error", "failed", "unable", "unhealthy");
    private static final List<String> ERROR_STOP_WORDS = List.of(
            "cannot", "error", "essential container", "failed", "outofmemory", "task failed", "unhealthy");

    private JavaEcsMonitorAgent() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(execute(args));
    }

    public static int execute(String[] args) {
        AgentArgs parsed = AgentArgs.parse(args);
        String region = parsed.get("region", "us-east-1");
        String cluster = parsed.get("cluster", "dynamodb-demo-cluster");
        String serviceName = parsed.get("service", "dynamodb-demo-service");
        String auditTable = parsed.get("audit-table", "AgentAudit");
        int eventLimit = parsed.getInt("event-limit", 10);
        int taskLimit = parsed.getInt("task-limit", 10);
        boolean dryRun = parsed.has("dry-run");
        boolean failOnBug = parsed.has("fail-on-bug");

        System.out.printf("[%s] Java ECS monitor checking %s/%s%n", AgentSupport.utcNow(), cluster, serviceName);

        List<Finding> findings;
        try (EcsClient ecs = EcsClient.builder().region(Region.of(region)).build()) {
            findings = collectFindings(ecs, cluster, serviceName, eventLimit, taskLimit);
        }

        if (findings.isEmpty()) {
            System.out.println("No ECS bugs detected.");
            return 0;
        }

        for (Finding finding : findings) {
            System.out.printf("Bug detected: [%s] %s%n", finding.severity(), finding.message());
            AuditWriter.put(region, auditTable, "java-ecs-monitor-agent", "BUG_DETECTED", "OPEN",
                    finding.bugType(), finding.severity(), finding.resource(), finding.message(), dryRun);
        }
        return failOnBug ? 1 : 0;
    }

    static List<Finding> collectFindings(EcsClient ecs, String cluster, String serviceName, int eventLimit, int taskLimit) {
        Service service = describeService(ecs, cluster, serviceName);
        List<Finding> findings = new ArrayList<>();
        String serviceArn = service.serviceArn() == null ? cluster + "/" + serviceName : service.serviceArn();

        if (service.runningCount() < service.desiredCount()) {
            findings.add(new Finding(
                    "SERVICE_CAPACITY",
                    "HIGH",
                    "Running tasks below desired count: running=%d, desired=%d, pending=%d"
                            .formatted(service.runningCount(), service.desiredCount(), service.pendingCount()),
                    serviceArn));
        }

        for (Deployment deployment : service.deployments()) {
            String taskDefinition = deployment.taskDefinition() == null ? "unknown-task-definition" : deployment.taskDefinition();
            if ("FAILED".equalsIgnoreCase(deployment.rolloutStateAsString())) {
                String reason = deployment.rolloutStateReason() == null ? "ECS deployment failed" : deployment.rolloutStateReason();
                findings.add(new Finding("DEPLOYMENT_FAILED", "CRITICAL", reason, taskDefinition));
            }
            if (deployment.failedTasks() != null && deployment.failedTasks() > 0) {
                findings.add(new Finding("FAILED_TASKS", "HIGH",
                        "ECS deployment has failedTasks=" + deployment.failedTasks(), taskDefinition));
            }
        }

        service.events().stream().limit(eventLimit).forEach(event -> addEventFinding(findings, event, serviceArn));

        List<Task> tasks = new ArrayList<>();
        tasks.addAll(describeTasks(ecs, cluster, serviceName, DesiredStatus.RUNNING, taskLimit));
        tasks.addAll(describeTasks(ecs, cluster, serviceName, DesiredStatus.STOPPED, taskLimit));
        for (Task task : tasks) {
            inspectTask(findings, task);
        }
        return findings;
    }

    private static Service describeService(EcsClient ecs, String cluster, String serviceName) {
        DescribeServicesResponse response = ecs.describeServices(request -> request.cluster(cluster).services(serviceName));
        if (!response.failures().isEmpty()) {
            throw new IllegalStateException("ECS describe-services failure: " + response.failures());
        }
        if (response.services().isEmpty()) {
            throw new IllegalStateException("ECS service not found: " + cluster + "/" + serviceName);
        }
        return response.services().get(0);
    }

    private static List<Task> describeTasks(
            EcsClient ecs,
            String cluster,
            String serviceName,
            DesiredStatus desiredStatus,
            int taskLimit) {
        ListTasksResponse listed = ecs.listTasks(request -> request
                .cluster(cluster)
                .serviceName(serviceName)
                .desiredStatus(desiredStatus));
        List<String> taskArns = listed.taskArns().stream().limit(taskLimit).toList();
        if (taskArns.isEmpty()) {
            return List.of();
        }
        DescribeTasksResponse described = ecs.describeTasks(request -> request.cluster(cluster).tasks(taskArns));
        return described.tasks();
    }

    private static void addEventFinding(List<Finding> findings, ServiceEvent event, String serviceArn) {
        String message = event.message() == null ? "" : event.message();
        String lower = message.toLowerCase(Locale.ROOT);
        if (BUG_WORDS.stream().anyMatch(lower::contains)) {
            findings.add(new Finding("ECS_EVENT", "MEDIUM", message, serviceArn));
        }
    }

    private static void inspectTask(List<Finding> findings, Task task) {
        String taskArn = task.taskArn() == null ? "unknown-task" : task.taskArn();
        String stopReason = task.stoppedReason() == null ? "" : task.stoppedReason();
        String lowerStopReason = stopReason.toLowerCase(Locale.ROOT);
        if (!stopReason.isBlank() && ERROR_STOP_WORDS.stream().anyMatch(lowerStopReason::contains)) {
            findings.add(new Finding("TASK_STOPPED", "HIGH", stopReason, taskArn));
        }
        for (Container container : task.containers()) {
            Integer exitCode = container.exitCode();
            String reason = container.reason() == null ? "" : container.reason();
            if ((exitCode != null && exitCode != 0) || !reason.isBlank()) {
                findings.add(new Finding("CONTAINER_ERROR", "HIGH",
                        "Container %s status=%s exitCode=%s reason=%s"
                                .formatted(container.name(), container.lastStatus(), exitCode, reason),
                        taskArn));
            }
        }
    }

    record Finding(String bugType, String severity, String message, String resource) {
    }
}
