package com.example.dynamodb_demo.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class JavaAutoFixAgent {

    private static final String LONG_EMPLOYEE_ID = "EMP-" + "X".repeat(25);
    private static final Pattern EMPLOYEE_ID_FIELD = Pattern.compile("\\bprivate\\s+String\\s+employeeId\\s*;");

    private JavaAutoFixAgent() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(execute(args));
    }

    public static int execute(String[] args) throws Exception {
        AgentArgs parsed = AgentArgs.parse(args);
        Path projectDir = Path.of(parsed.get("project-dir", ".")).toAbsolutePath().normalize();
        String region = parsed.get("region", "us-east-1");
        String auditTable = parsed.get("audit-table", "AgentAudit");
        String baseUrl = parsed.get("base-url", "");
        boolean dryRun = parsed.has("dry-run");
        boolean applyFix = !parsed.has("no-apply") && !dryRun;
        boolean runTests = !parsed.has("skip-tests");

        System.out.printf("[%s] Java auto-fix agent checking employeeId validation%n", AgentSupport.utcNow());

        Integer deployedStatus = null;
        if (!baseUrl.isBlank()) {
            deployedStatus = deployedValidationStatus(baseUrl);
            System.out.printf("Deployed validation check returned HTTP %d%n", deployedStatus);
            if (deployedStatus != 400) {
                String message = "Bug detected: employeeId longer than 20 chars returned HTTP "
                        + deployedStatus + "; expected 400.";
                System.out.println("Notification: " + message);
                AuditWriter.put(region, auditTable, "java-auto-fix-agent", "BUG_DETECTED", "OPEN",
                        "VALIDATION", "HIGH", employeeModelPath(projectDir).toString(), message, dryRun);
            }
        }

        FixResult fix = ensureEmployeeIdValidation(projectDir, applyFix);
        System.out.println("Notification: " + fix.message());

        if (dryRun) {
            return 0;
        }

        if (fix.changed()) {
            AuditWriter.put(region, auditTable, "java-auto-fix-agent", "BUG_FIXED_AUTOMATICALLY", "FIX_APPLIED",
                    "VALIDATION", "MEDIUM", employeeModelPath(projectDir).toString(), fix.message(), false);
            if (runTests) {
                TestResult test = runTests(projectDir);
                System.out.println("Notification: " + test.message());
                AuditWriter.put(region, auditTable, "java-auto-fix-agent",
                        test.passed() ? "AUTO_FIX_VALIDATED" : "AUTO_FIX_VALIDATION_FAILED",
                        test.passed() ? "TEST_PASSED" : "TEST_FAILED",
                        "VALIDATION",
                        test.passed() ? "MEDIUM" : "HIGH",
                        projectDir.toString(),
                        test.message(),
                        false);
                return test.passed() ? 0 : 1;
            }
            return 0;
        }

        if (deployedStatus == null || deployedStatus == 400) {
            AuditWriter.put(region, auditTable, "java-auto-fix-agent", "FIX_NOT_REQUIRED", "NO_ACTION",
                    "VALIDATION", "LOW", employeeModelPath(projectDir).toString(), fix.message(), false);
            return 0;
        }

        String message = "Source already contains validation fix; redeploy may be needed for the running ECS task.";
        System.out.println("Notification: " + message);
        AuditWriter.put(region, auditTable, "java-auto-fix-agent", "FIX_NOT_APPLIED", "REDEPLOY_RECOMMENDED",
                "VALIDATION", "HIGH", employeeModelPath(projectDir).toString(), message, false);
        return 1;
    }

    static FixResult ensureEmployeeIdValidation(Path projectDir, boolean applyFix) throws IOException {
        Path path = employeeModelPath(projectDir);
        String text = Files.readString(path);
        List<String> lines = new ArrayList<>(text.lines().toList());
        int fieldIndex = findEmployeeIdLine(lines);

        if (hasCorrectEmployeeIdSize(lines, fieldIndex)) {
            return new FixResult(false, "employeeId already has @Size(max = 20); no source fix needed.");
        }

        boolean changed = false;
        if (!text.contains("import jakarta.validation.constraints.Size;")) {
            int importIndex = 0;
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).startsWith("import ")) {
                    importIndex = index;
                    if (lines.get(index).equals("import jakarta.validation.constraints.NotBlank;")) {
                        break;
                    }
                }
            }
            lines.add(importIndex + 1, "import jakarta.validation.constraints.Size;");
            fieldIndex++;
            changed = true;
        }

        int annotationStart = fieldIndex;
        while (annotationStart > 0 && lines.get(annotationStart - 1).trim().startsWith("@")) {
            annotationStart--;
        }

        boolean replacedExistingSize = false;
        for (int index = annotationStart; index < fieldIndex; index++) {
            if (lines.get(index).trim().startsWith("@Size")) {
                lines.set(index, "    @Size(max = 20)");
                replacedExistingSize = true;
                changed = true;
                break;
            }
        }

        if (!replacedExistingSize) {
            int insertIndex = annotationStart;
            for (int index = annotationStart; index < fieldIndex; index++) {
                if (lines.get(index).trim().startsWith("@NotBlank")) {
                    insertIndex = index + 1;
                    break;
                }
            }
            lines.add(insertIndex, "    @Size(max = 20)");
            changed = true;
        }

        if (!changed) {
            return new FixResult(false, "No source change was required.");
        }
        if (applyFix) {
            Files.writeString(path, String.join("\n", lines) + "\n");
            return new FixResult(true, "Applied automatic fix: added @Size(max = 20) validation to employeeId.");
        }
        return new FixResult(false, "Validation fix is needed, but no file changed because apply mode is disabled.");
    }

    private static Path employeeModelPath(Path projectDir) {
        return projectDir.resolve("src/main/java/com/example/dynamodb_demo/model/Employee.java");
    }

    private static int findEmployeeIdLine(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (EMPLOYEE_ID_FIELD.matcher(lines.get(index)).find()) {
                return index;
            }
        }
        throw new IllegalStateException("Could not find employeeId field in Employee.java");
    }

    private static boolean hasCorrectEmployeeIdSize(List<String> lines, int fieldIndex) {
        int start = Math.max(0, fieldIndex - 8);
        for (int index = start; index < fieldIndex; index++) {
            String line = lines.get(index);
            if (line.contains("@Size(max = 20)") || line.contains("@Size(max=20)")) {
                return true;
            }
        }
        return false;
    }

    private static Integer deployedValidationStatus(String baseUrl) throws IOException, InterruptedException {
        String payload = """
                {"employeeId":"%s","name":"Validation Check","department":"Quality"}
                """.formatted(LONG_EMPLOYEE_ID);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/$", "") + "/api/employees"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static TestResult runTests(Path projectDir) throws IOException, InterruptedException {
        AgentSupport.CommandResult result = AgentSupport.run(projectDir, List.of("./mvnw", "test", "-q"), false);
        if (result.exitCode() == 0) {
            return new TestResult(true, "Tests passed after auto-fix.");
        }
        String output = (result.stdout() + "\n" + result.stderr()).trim();
        String detail = output.length() > 900 ? output.substring(output.length() - 900) : output;
        return new TestResult(false, "Tests failed after auto-fix: " + detail);
    }

    record FixResult(boolean changed, String message) {
    }

    record TestResult(boolean passed, String message) {
    }
}
