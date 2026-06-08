package com.example.dynamodb_demo.repository;

import com.example.dynamodb_demo.model.Employee;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class EmployeeRepository {

    private final DynamoDbClient dynamoDbClient;

    public void save(Employee employee) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("employeeId", AttributeValue.fromS(employee.getEmployeeId()));
        item.put("name", AttributeValue.fromS(employee.getName()));
        item.put("department", AttributeValue.fromS(employee.getDepartment()));

        PutItemRequest request = PutItemRequest.builder()
                .tableName("EmployeeTable")
                .item(item)
                .build();

        dynamoDbClient.putItem(request);
    }
}
