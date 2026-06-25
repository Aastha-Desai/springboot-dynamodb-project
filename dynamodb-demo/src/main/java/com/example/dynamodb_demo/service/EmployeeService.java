
package com.example.dynamodb_demo.service;

import com.example.dynamodb_demo.model.Employee;
import com.example.dynamodb_demo.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository repository;

    public Employee saveEmployee(Employee employee) {
        log.debug("Saving employee with employeeId={}", employee.getEmployeeId());
        repository.save(employee);
        log.debug("Saved employee with employeeId={}", employee.getEmployeeId());
        return employee;
    }

    public Optional<Employee> findById(String id) {
        log.debug("Looking up employee with employeeId={}", id);
        Optional<Employee> employee = repository.findById(id);
        log.debug("Lookup result for employeeId={} found={}", id, employee.isPresent());
        return employee;
    }

    public List<Employee> findAll() {
        log.debug("Listing all employees");
        List<Employee> employees = repository.findAll();
        log.debug("Listed {} employees", employees.size());
        return employees;
    }
}
