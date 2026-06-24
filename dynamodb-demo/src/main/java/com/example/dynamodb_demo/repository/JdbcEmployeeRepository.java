package com.example.dynamodb_demo.repository;

import com.example.dynamodb_demo.model.Employee;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"local", "test"})
@RequiredArgsConstructor
public class JdbcEmployeeRepository implements EmployeeRepository {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void createTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS employees (
                    employee_id VARCHAR(20) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    department VARCHAR(255) NOT NULL
                )
                """);
    }

    @Override
    public void save(Employee employee) {
        jdbcTemplate.update("""
                        MERGE INTO employees (employee_id, name, department)
                        KEY(employee_id)
                        VALUES (?, ?, ?)
                        """,
                employee.getEmployeeId(),
                employee.getName(),
                employee.getDepartment());
    }

    @Override
    public Optional<Employee> findById(String employeeId) {
        List<Employee> employees = jdbcTemplate.query(
                "SELECT employee_id, name, department FROM employees WHERE employee_id = ?",
                (rs, rowNum) -> new Employee(
                        rs.getString("employee_id"),
                        rs.getString("name"),
                        rs.getString("department")),
                employeeId);
        return employees.stream().findFirst();
    }

    @Override
    public List<Employee> findAll() {
        return jdbcTemplate.query(
                "SELECT employee_id, name, department FROM employees ORDER BY employee_id",
                (rs, rowNum) -> new Employee(
                        rs.getString("employee_id"),
                        rs.getString("name"),
                        rs.getString("department")));
    }
}
