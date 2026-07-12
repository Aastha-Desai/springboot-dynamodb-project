package com.example.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

public final class JavaAuditReportAgent {

    private JavaAuditReportAgent() {
    }

    public static void main(String[] args) {
        AgentArgs parsed = AgentArgs.parse(args);
        String region = parsed.get("region", "us-east-1");
        String table = parsed.get("audit-table", "AgentAudit");
        int limit = parsed.getInt("limit", 25);

        List<AuditEvent> events = loadAuditEvents(region, table);
        events.sort(Comparator.comparing(AuditEvent::eventTime).reversed());
        List<AuditEvent> visible = events.stream().limit(limit).toList();

        System.out.printf("Latest %d audit events from %s in %s%n", visible.size(), table, region);
        System.out.println("----------------------------------------------------------------------------------------------------");
        System.out.printf("%-22s %-28s %-24s %-18s %s%n",
                "eventTime", "eventType", "status", "source", "message");
        System.out.println("----------------------------------------------------------------------------------------------------");
        for (AuditEvent event : visible) {
            System.out.printf("%-22s %-28s %-24s %-18s %s%n",
                    trim(event.eventTime(), 22),
                    trim(event.eventType(), 28),
                    trim(event.status(), 24),
                    trim(event.source(), 18),
                    trim(event.message(), 90));
        }
    }

    private static List<AuditEvent> loadAuditEvents(String region, String table) {
        List<AuditEvent> events = new ArrayList<>();
        try (DynamoDbClient client = DynamoDbClient.builder().region(Region.of(region)).build()) {
            Map<String, AttributeValue> startKey = null;
            do {
                ScanRequest.Builder request = ScanRequest.builder().tableName(table);
                if (startKey != null && !startKey.isEmpty()) {
                    request.exclusiveStartKey(startKey);
                }
                ScanResponse response = client.scan(request.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    events.add(new AuditEvent(
                            value(item, "eventTime"),
                            value(item, "eventType"),
                            value(item, "status"),
                            value(item, "source"),
                            value(item, "message")));
                }
                startKey = response.lastEvaluatedKey();
            } while (startKey != null && !startKey.isEmpty());
        }
        return events;
    }

    private static String value(Map<String, AttributeValue> item, String key) {
        AttributeValue value = item.get(key);
        return value == null || value.s() == null ? "" : value.s();
    }

    private static String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record AuditEvent(String eventTime, String eventType, String status, String source, String message) {
    }
}
