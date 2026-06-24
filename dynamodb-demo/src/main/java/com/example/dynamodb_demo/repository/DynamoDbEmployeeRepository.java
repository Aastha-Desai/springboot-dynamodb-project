package com.example.dynamodb_demo.repository;

import com.example.dynamodb_demo.model.Employee;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

@Repository
@Profile("!local & !test")
@RequiredArgsConstructor
public class DynamoDbEmployeeRepository implements EmployeeRepository {

    private final DynamoDbClient dynamoDbClient;

    @Value("${app.dynamodb.table-name:EmployeeTable}")
    private String tableName;

    @Override
    public void save(Employee employee) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("employeeId", AttributeValue.fromS(employee.getEmployeeId()));
        item.put("name", AttributeValue.fromS(employee.getName()));
        item.put("department", AttributeValue.fromS(employee.getDepartment()));

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

        dynamoDbClient.putItem(request);
    }

    @Override
    public Optional<Employee> findById(String employeeId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("employeeId", AttributeValue.fromS(employeeId));

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build();

        GetItemResponse response = dynamoDbClient.getItem(request);
        if (response.hasItem() && !response.item().isEmpty()) {
            Map<String, AttributeValue> item = response.item();
            Employee e = new Employee(
                    item.get("employeeId").s(),
                    item.getOrDefault("name", AttributeValue.fromS("")).s(),
                    item.getOrDefault("department", AttributeValue.fromS("")).s()
            );
            return Optional.of(e);
        }
        return Optional.empty();
    }

    @Override
    public List<Employee> findAll() {
        ScanRequest request = ScanRequest.builder()
                .tableName(tableName)
                .build();

        ScanResponse response = dynamoDbClient.scan(request);
        List<Employee> results = new ArrayList<>();
        for (Map<String, AttributeValue> item : response.items()) {
            Employee e = new Employee(
                    item.getOrDefault("employeeId", AttributeValue.fromS("")).s(),
                    item.getOrDefault("name", AttributeValue.fromS("")).s(),
                    item.getOrDefault("department", AttributeValue.fromS("")).s()
            );
            results.add(e);
        }
        return results;
    }
}
