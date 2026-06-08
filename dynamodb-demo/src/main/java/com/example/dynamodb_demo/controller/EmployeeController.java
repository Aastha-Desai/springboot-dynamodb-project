package com.example.dynamodb_demo.controller;

import com.example.dynamodb_demo.model.Employee;
import com.example.dynamodb_demo.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService service;



@PostMapping
public ResponseEntity<String> createEmployee(@RequestBody Employee employee) {
    service.saveEmployee(employee);
    return ResponseEntity.ok("Employee saved");
}

}
