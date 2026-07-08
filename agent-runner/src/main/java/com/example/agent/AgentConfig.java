package com.example.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentConfig(
        String projectName,
        String repoDir,
        String projectDir,
        String agentPrompt,
        AwsConfig aws,
        List<String> testCommand,
        List<ValidationRule> validationRules) {

    static AgentConfig load(Path configPath) throws IOException {
        Object parsed = new JsonParser(Files.readString(configPath)).parse();
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Agent config root must be a JSON object.");
        }

        Map<String, Object> map = asStringMap(root);
        Map<String, Object> awsMap = asStringMap(map.get("aws"));
        List<Map<String, Object>> rules = asObjectList(map.get("validationRules"));

        return new AgentConfig(
                stringValue(map.get("projectName")),
                stringValue(map.get("repoDir")),
                stringValue(map.get("projectDir")),
                stringValue(map.get("agentPrompt")),
                new AwsConfig(
                        stringValue(awsMap.get("region")),
                        stringValue(awsMap.get("cluster")),
                        stringValue(awsMap.get("service")),
                        stringValue(awsMap.get("auditTable"))),
                asStringList(map.get("testCommand")),
                rules.stream().map(AgentConfig::ruleFrom).toList());
    }

    static Path defaultConfigPath() {
        return Path.of("../agent-config/project-config.json").toAbsolutePath().normalize();
    }

    static String toJson(Map<String, Object> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(escape(entry.getKey())).append('"').append(':');
            Object value = entry.getValue();
            if (value == null) {
                builder.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                builder.append(value);
            } else {
                builder.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return builder.append('}').toString();
    }

    List<ValidationRule> rules() {
        return validationRules == null ? List.of() : validationRules;
    }

    List<String> effectiveTestCommand() {
        return testCommand == null || testCommand.isEmpty() ? List.of("./mvnw", "test", "-q") : testCommand;
    }

    private static ValidationRule ruleFrom(Map<String, Object> rule) {
        return new ValidationRule(
                stringValue(rule.get("name")),
                stringValue(rule.get("type")),
                stringValue(rule.get("file")),
                stringValue(rule.get("field")),
                stringValue(rule.get("requiredImport")),
                stringValue(rule.get("annotation")),
                stringValue(rule.get("bugType")),
                stringValue(rule.get("severity")),
                stringValue(rule.get("validationEndpoint")),
                intValue(rule.get("expectedFailureStatus")),
                asStringMap(rule.get("invalidPayload")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object but got " + value);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static List<Map<String, Object>> asObjectList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array but got " + value);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(asStringMap(item));
        }
        return result;
    }

    private static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected JSON array but got " + value);
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record AwsConfig(String region, String cluster, String service, String auditTable) {
    }

    public record ValidationRule(
            String name,
            String type,
            String file,
            String field,
            String requiredImport,
            String annotation,
            String bugType,
            String severity,
            String validationEndpoint,
            Integer expectedFailureStatus,
            Map<String, Object> invalidPayload) {

        String effectiveBugType() {
            return bugType == null || bugType.isBlank() ? "VALIDATION" : bugType;
        }

        String effectiveSeverity() {
            return severity == null || severity.isBlank() ? "MEDIUM" : severity;
        }

        int effectiveExpectedFailureStatus() {
            return expectedFailureStatus == null ? 400 : expectedFailureStatus;
        }

        Map<String, Object> payload() {
            return invalidPayload == null ? new LinkedHashMap<>() : invalidPayload;
        }
    }

    private static final class JsonParser {
        private final String text;
        private int index;

        private JsonParser(String text) {
            this.text = text;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException("Unexpected JSON after index " + index);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (peek('{')) {
                return parseObject();
            }
            if (peek('[')) {
                return parseArray();
            }
            if (peek('"')) {
                return parseString();
            }
            if (match("true")) {
                return Boolean.TRUE;
            }
            if (match("false")) {
                return Boolean.FALSE;
            }
            if (match("null")) {
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return map;
            }
            while (true) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return map;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return values;
            }
            while (true) {
                values.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return values;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\') {
                    if (index >= text.length()) {
                        throw new IllegalArgumentException("Invalid JSON escape at end of text.");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        default -> throw new IllegalArgumentException("Unsupported JSON escape: \\" + escaped);
                    }
                } else {
                    builder.append(current);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string.");
        }

        private Number parseNumber() {
            int start = index;
            while (index < text.length()) {
                char current = text.charAt(index);
                if ((current >= '0' && current <= '9') || current == '-') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("Expected JSON value at index " + index);
            }
            return Integer.parseInt(text.substring(start, index));
        }

        private boolean match(String literal) {
            if (text.startsWith(literal, index)) {
                index += literal.length();
                return true;
            }
            return false;
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + index);
            }
            index++;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}
