package com.example.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesResponse;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Attachment;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;
import software.amazon.awssdk.services.ecs.model.Task;

public final class JavaValidateEcsDeploymentAgent {

    private JavaValidateEcsDeploymentAgent() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(execute(args));
    }

    public static int execute(String[] args) throws Exception {
        AgentArgs parsed = AgentArgs.parse(args);
        Path configPath = Path.of(parsed.get("config", AgentConfig.defaultConfigPath().toString()))
                .toAbsolutePath()
                .normalize();
        AgentConfig config = AgentConfig.load(configPath);
        String region = parsed.get("region", config.aws().region());
        String cluster = parsed.get("cluster", config.aws().cluster());
        String service = parsed.get("service", config.aws().service());
        String auditTable = parsed.get("audit-table", config.aws().auditTable());
        String explicitBaseUrl = parsed.get("base-url", "");
        int port = parsed.getInt("port", 8080);
        boolean skipAudit = parsed.has("skip-audit");
        boolean forceFail = parsed.has("force-fail");

        String baseUrl = explicitBaseUrl.isBlank() ? publicBaseUrl(region, cluster, service, port) : stripTrailingSlash(explicitBaseUrl);
        System.out.printf("[%s] Java deployment validator checking ECS deployment at %s%n", AgentSupport.utcNow(), baseUrl);

        List<String> failures = validate(baseUrl, config.rules());
        if (forceFail) {
            failures.add("Forced validation failure requested for rollback test.");
        }

        if (!failures.isEmpty()) {
            String message = "Deployment validation failed: " + String.join(" | ", failures);
            System.out.println(message);
            AuditWriter.put(region, auditTable, "java-deployment-validation-agent",
                    "DEPLOYMENT_VALIDATION_FAILED", "FAILED", "DEPLOYMENT_VALIDATION",
                    "HIGH", baseUrl, message, skipAudit);
            return 1;
        }

        String message = "Deployment validation succeeded: Swagger, POST/GET employee, and address special-character validation passed.";
        System.out.println(message);
        AuditWriter.put(region, auditTable, "java-deployment-validation-agent",
                "DEPLOYMENT_VALIDATED", "PASSED", "DEPLOYMENT_VALIDATION",
                "LOW", baseUrl, message, skipAudit);
        return 0;
    }

    static String publicBaseUrl(String region, String cluster, String service, int port) {
        Region awsRegion = Region.of(region);
        try (EcsClient ecs = EcsClient.builder().region(awsRegion).build();
             Ec2Client ec2 = Ec2Client.builder().region(awsRegion).build()) {
            ListTasksResponse listed = ecs.listTasks(request -> request
                    .cluster(cluster)
                    .serviceName(service)
                    .desiredStatus("RUNNING"));
            if (listed.taskArns().isEmpty()) {
                throw new IllegalStateException("No running ECS tasks found for " + cluster + "/" + service);
            }

            Task task = ecs.describeTasks(request -> request.cluster(cluster).tasks(listed.taskArns().get(0)))
                    .tasks()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not describe running ECS task."));
            Attachment attachment = task.attachments()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Running task has no network attachment: " + task.taskArn()));
            String eniId = attachment.details()
                    .stream()
                    .filter(detail -> "networkInterfaceId".equals(detail.name()))
                    .map(KeyValuePair::value)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not resolve networkInterfaceId for task: " + task.taskArn()));

            DescribeNetworkInterfacesResponse eniResponse = ec2.describeNetworkInterfaces(request -> request.networkInterfaceIds(eniId));
            NetworkInterface networkInterface = eniResponse.networkInterfaces()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Could not describe ENI " + eniId));
            if (networkInterface.association() == null || networkInterface.association().publicIp() == null) {
                throw new IllegalStateException("ECS task ENI " + eniId + " does not have a public IP. Use --base-url with a load balancer URL.");
            }
            return "http://" + networkInterface.association().publicIp() + ":" + port;
        }
    }

    static List<String> validate(String baseUrl, List<AgentConfig.ValidationRule> rules) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        List<String> failures = new ArrayList<>();

        HttpResult apiDocs = request(client, baseUrl + "/v3/api-docs", "GET", "");
        if (apiDocs.status() != 200 || !apiDocs.body().contains("Employee API")) {
            failures.add("Swagger validation failed: HTTP " + apiDocs.status());
        }

        String employeeId = "EMP-" + Instant.now().getEpochSecond();
        String employeePayload = """
                {"employeeId":"%s","name":"Deployment Validator","department":"Quality","address":"123 Main St, Newark NJ"}
                """.formatted(employeeId);
        HttpResult post = request(client, baseUrl + "/api/employees", "POST", employeePayload);
        if (post.status() != 201) {
            failures.add("Employee POST failed: HTTP " + post.status() + " body=" + truncate(post.body()));
        }

        HttpResult get = request(client, baseUrl + "/api/employees/" + employeeId, "GET", "");
        if (get.status() != 200 || !get.body().contains(employeeId)) {
            failures.add("Employee GET failed: HTTP " + get.status() + " body=" + truncate(get.body()));
        }

        for (AgentConfig.ValidationRule rule : rules) {
            if (rule.validationEndpoint() == null || rule.validationEndpoint().isBlank() || rule.payload().isEmpty()) {
                continue;
            }
            String endpoint = rule.validationEndpoint().startsWith("/")
                    ? rule.validationEndpoint()
                    : "/" + rule.validationEndpoint();
            HttpResult invalid = request(client, baseUrl + endpoint, "POST", AgentConfig.toJson(rule.payload()));
            if (invalid.status() != rule.effectiveExpectedFailureStatus()) {
                failures.add("Rule %s failed: expected HTTP %d, got HTTP %d body=%s"
                        .formatted(rule.name(), rule.effectiveExpectedFailureStatus(), invalid.status(), truncate(invalid.body())));
            }
        }
        return failures;
    }

    private static HttpResult request(HttpClient client, String url, String method, String payload)
            throws InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(java.time.Duration.ofSeconds(20));
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
        } else {
            builder.GET();
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body());
        } catch (IOException exc) {
            return new HttpResult(0, exc.getMessage());
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    record HttpResult(int status, String body) {
    }
}
