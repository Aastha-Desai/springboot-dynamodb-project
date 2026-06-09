
package com.example.dynamodb_demo.service;

import com.example.dynamodb_demo.model.Employee;
import com.example.dynamodb_demo.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository repository;

    public Employee saveEmployee(Employee employee) {
        repository.save(employee);
        return employee;
    }

    public Optional<Employee> findById(String id) {
        return repository.findById(id);
    }

    public List<Employee> findAll() {
        return repository.findAll();
    }
}
