package com.example.dynamodb_demo.agent;

import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

final class AuditWriter {

    private AuditWriter() {
    }

    static void put(
            String region,
            String table,
            String source,
            String eventType,
            String status,
            String bugType,
            String severity,
            String resource,
            String message,
            boolean dryRun) {
        if (dryRun) {
            System.out.printf("Audit dry-run: %s %s - %s%n", eventType, status, message);
            return;
        }
        String eventTime = AgentSupport.utcNow();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("auditId", AttributeValue.fromS(AgentSupport.stableId(eventTime + "|" + eventType + "|" + message)));
        item.put("eventTime", AttributeValue.fromS(eventTime));
        item.put("source", AttributeValue.fromS(source));
        item.put("eventType", AttributeValue.fromS(eventType));
        item.put("bugType", AttributeValue.fromS(bugType));
        item.put("severity", AttributeValue.fromS(severity));
        item.put("status", AttributeValue.fromS(status));
        item.put("resource", AttributeValue.fromS(resource));
        item.put("message", AttributeValue.fromS(message.length() > 900 ? message.substring(0, 900) : message));

        try (DynamoDbClient client = DynamoDbClient.builder().region(Region.of(region)).build()) {
            client.putItem(PutItemRequest.builder().tableName(table).item(item).build());
        }
    }
}
