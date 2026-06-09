package com.example.dynamodb_demo.controller;

import com.example.dynamodb_demo.model.Employee;
import com.example.dynamodb_demo.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
@Tag(name = "Employee API", description = "Create employee records")
public class EmployeeController {

    private final EmployeeService service;

    @PostMapping
    @Operation(summary = "Create an employee", description = "Accepts an employee JSON payload and stores it in DynamoDB")
    @ApiResponse(responseCode = "201", description = "Employee created")
    @ApiResponse(responseCode = "400", description = "Invalid employee payload")
    public ResponseEntity<Employee> createEmployee(@Valid @RequestBody Employee employee) {
        Employee savedEmployee = service.saveEmployee(employee);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedEmployee);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an employee by id")
    public ResponseEntity<Employee> getEmployee(@PathVariable("id") String id) {
        Optional<Employee> e = service.findById(id);
        return e.map(employee -> ResponseEntity.ok(employee))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List all employees")
    public ResponseEntity<List<Employee>> listEmployees() {
        List<Employee> list = service.findAll();
        return ResponseEntity.ok(list);
    }
}
