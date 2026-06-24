package com.example.dynamodb_demo.repository;

import com.example.dynamodb_demo.model.Employee;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository {

    void save(Employee employee);

    Optional<Employee> findById(String employeeId);

    List<Employee> findAll();
}
