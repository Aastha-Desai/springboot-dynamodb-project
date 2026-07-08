package com.example.agent;

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

    private JavaAutoFixAgent() {
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
        Path configDir = configPath.getParent();
        Path projectDir = Path.of(parsed.get("project-dir", resolve(configDir, config.projectDir()).toString()))
                .toAbsolutePath()
                .normalize();
        String region = parsed.get("region", config.aws().region());
        String auditTable = parsed.get("audit-table", config.aws().auditTable());
        String baseUrl = parsed.get("base-url", "");
        boolean dryRun = parsed.has("dry-run");
        boolean applyFix = !parsed.has("no-apply") && !dryRun;
        boolean runTests = !parsed.has("skip-tests");

        System.out.printf("[%s] Java auto-fix agent checking configured validation rules%n", AgentSupport.utcNow());

        List<RuleCheck> checks = new ArrayList<>();
        if (!baseUrl.isBlank()) {
            for (AgentConfig.ValidationRule rule : config.rules()) {
                if (rule.validationEndpoint() == null || rule.validationEndpoint().isBlank() || rule.payload().isEmpty()) {
                    continue;
                }
                int status = deployedValidationStatus(baseUrl, rule);
                checks.add(new RuleCheck(rule, status));
                System.out.printf("Rule %s validation check returned HTTP %d%n", rule.name(), status);
                if (status != rule.effectiveExpectedFailureStatus()) {
                    String message = "Bug detected: rule %s returned HTTP %d; expected %d."
                            .formatted(rule.name(), status, rule.effectiveExpectedFailureStatus());
                    System.out.println("Notification: " + message);
                    AuditWriter.put(region, auditTable, "java-auto-fix-agent", "BUG_DETECTED", "OPEN",
                            rule.effectiveBugType(), rule.effectiveSeverity(), rule.file(), message, dryRun);
                }
            }
        }

        FixResult fix = ensureConfiguredValidationRules(projectDir, config.rules(), applyFix);
        System.out.println("Notification: " + fix.message());

        if (dryRun) {
            return 0;
        }

        if (fix.changed()) {
            AuditWriter.put(region, auditTable, "java-auto-fix-agent", "BUG_FIXED_AUTOMATICALLY", "FIX_APPLIED",
                    "VALIDATION", "MEDIUM", projectDir.toString(), fix.message(), false);
            if (runTests) {
                TestResult test = runTests(projectDir, config.effectiveTestCommand());
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

        boolean deployedRulesAlreadyPass = checks.stream()
                .allMatch(check -> check.status() == check.rule().effectiveExpectedFailureStatus());
        if (checks.isEmpty() || deployedRulesAlreadyPass) {
            AuditWriter.put(region, auditTable, "java-auto-fix-agent", "FIX_NOT_REQUIRED", "NO_ACTION",
                    "VALIDATION", "LOW", projectDir.toString(), fix.message(), false);
            return 0;
        }

        String message = "Source already contains configured validation rules; redeploy may be needed for the running ECS task.";
        System.out.println("Notification: " + message);
        AuditWriter.put(region, auditTable, "java-auto-fix-agent", "FIX_NOT_APPLIED", "REDEPLOY_RECOMMENDED",
                "VALIDATION", "HIGH", projectDir.toString(), message, false);
        return 1;
    }

    static FixResult ensureConfiguredValidationRules(
            Path projectDir,
            List<AgentConfig.ValidationRule> rules,
            boolean applyFix) throws IOException {
        List<String> messages = new ArrayList<>();
        boolean changed = false;

        for (AgentConfig.ValidationRule rule : rules) {
            if (!"java-field-annotation".equals(rule.type())) {
                messages.add("Skipped unsupported rule type for %s: %s".formatted(rule.name(), rule.type()));
                continue;
            }
            FixResult result = ensureJavaFieldAnnotation(projectDir, rule, applyFix);
            changed = changed || result.changed();
            messages.add(result.message());
        }

        if (messages.isEmpty()) {
            return new FixResult(false, "No configured validation rules found.");
        }
        return new FixResult(changed, String.join(" | ", messages));
    }

    private static FixResult ensureJavaFieldAnnotation(
            Path projectDir,
            AgentConfig.ValidationRule rule,
            boolean applyFix) throws IOException {
        Path path = projectDir.resolve(rule.file()).normalize();
        String text = Files.readString(path);
        List<String> lines = new ArrayList<>(text.lines().toList());
        int fieldIndex = findFieldLine(lines, rule.field());

        if (hasAnnotation(lines, fieldIndex, rule.annotation())) {
            return new FixResult(false, "Rule %s already exists on field %s; no source fix needed."
                    .formatted(rule.name(), rule.field()));
        }

        boolean changed = false;
        if (rule.requiredImport() != null && !rule.requiredImport().isBlank()) {
            String importLine = "import " + rule.requiredImport() + ";";
            if (!text.contains(importLine)) {
                int importIndex = findImportInsertIndex(lines);
                lines.add(importIndex + 1, importLine);
                fieldIndex++;
                changed = true;
            }
        }

        int annotationStart = fieldIndex;
        while (annotationStart > 0 && lines.get(annotationStart - 1).trim().startsWith("@")) {
            annotationStart--;
        }

        String annotationPrefix = annotationPrefix(rule.annotation());
        boolean replacedExistingAnnotation = false;
        for (int index = annotationStart; index < fieldIndex; index++) {
            if (lines.get(index).trim().startsWith(annotationPrefix)) {
                lines.set(index, "    " + rule.annotation());
                replacedExistingAnnotation = true;
                changed = true;
                break;
            }
        }

        if (!replacedExistingAnnotation) {
            int insertIndex = annotationStart;
            for (int index = annotationStart; index < fieldIndex; index++) {
                if (lines.get(index).trim().startsWith("@NotBlank")) {
                    insertIndex = index + 1;
                    break;
                }
            }
            lines.add(insertIndex, "    " + rule.annotation());
            changed = true;
        }

        if (!changed) {
            return new FixResult(false, "No source change was required for rule " + rule.name() + ".");
        }
        if (applyFix) {
            Files.writeString(path, String.join("\n", lines) + "\n");
            return new FixResult(true, "Applied automatic fix for rule %s on field %s."
                    .formatted(rule.name(), rule.field()));
        }
        return new FixResult(false, "Rule %s needs a fix, but apply mode is disabled.".formatted(rule.name()));
    }

    private static int findFieldLine(List<String> lines, String fieldName) {
        Pattern fieldPattern = Pattern.compile("\\bprivate\\s+\\w+(?:<[^>]+>)?\\s+" + Pattern.quote(fieldName) + "\\s*;");
        for (int index = 0; index < lines.size(); index++) {
            if (fieldPattern.matcher(lines.get(index)).find()) {
                return index;
            }
        }
        throw new IllegalStateException("Could not find field " + fieldName + " in configured Java file.");
    }

    private static boolean hasAnnotation(List<String> lines, int fieldIndex, String annotation) {
        String expected = normalizeAnnotation(annotation);
        int start = Math.max(0, fieldIndex - 8);
        for (int index = start; index < fieldIndex; index++) {
            if (normalizeAnnotation(lines.get(index).trim()).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static int findImportInsertIndex(List<String> lines) {
        int importIndex = 0;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("import ")) {
                importIndex = index;
            }
        }
        return importIndex;
    }

    private static String annotationPrefix(String annotation) {
        int paren = annotation.indexOf('(');
        String prefix = paren >= 0 ? annotation.substring(0, paren) : annotation;
        return prefix.trim();
    }

    private static String normalizeAnnotation(String value) {
        return value.replaceAll("\\s+", "");
    }

    private static Integer deployedValidationStatus(String baseUrl, AgentConfig.ValidationRule rule)
            throws IOException, InterruptedException {
        String payload = AgentConfig.toJson(rule.payload());
        String endpoint = rule.validationEndpoint().startsWith("/")
                ? rule.validationEndpoint()
                : "/" + rule.validationEndpoint();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/$", "") + endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static TestResult runTests(Path projectDir, List<String> testCommand) throws IOException, InterruptedException {
        AgentSupport.CommandResult result = AgentSupport.run(projectDir, testCommand, false);
        if (result.exitCode() == 0) {
            return new TestResult(true, "Tests passed after auto-fix.");
        }
        String output = (result.stdout() + "\n" + result.stderr()).trim();
        String detail = output.length() > 900 ? output.substring(output.length() - 900) : output;
        return new TestResult(false, "Tests failed after auto-fix: " + detail);
    }

    private static Path resolve(Path baseDir, String value) {
        if (value == null || value.isBlank()) {
            return baseDir;
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
    }

    record FixResult(boolean changed, String message) {
    }

    record RuleCheck(AgentConfig.ValidationRule rule, int status) {
    }

    record TestResult(boolean passed, String message) {
    }
}
