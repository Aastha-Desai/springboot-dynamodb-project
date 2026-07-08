package com.example.dynamodb_demo.repository;

import com.example.dynamodb_demo.model.Employee;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

@Repository
@Profile({"local", "test"})
@RequiredArgsConstructor
public class JdbcEmployeeRepository implements EmployeeRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcEmployeeRepository.class);

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void createTable() {
        log.info("Initializing local/test H2 employees table");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS employees (
                    employee_id VARCHAR(20) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    department VARCHAR(255) NOT NULL,
                    address VARCHAR(255) NOT NULL
                )
                """);
    }

    @Override
    public void save(Employee employee) {
        log.debug("Saving employeeId={} to H2 employees table", employee.getEmployeeId());
        jdbcTemplate.update("""
                        MERGE INTO employees (employee_id, name, department, address)
                        KEY(employee_id)
                        VALUES (?, ?, ?, ?)
                        """,
                employee.getEmployeeId(),
                employee.getName(),
                employee.getDepartment(),
                employee.getAddress());
        log.info("Saved employeeId={} to H2 employees table", employee.getEmployeeId());
    }

    @Override
    public Optional<Employee> findById(String employeeId) {
        log.debug("Reading employeeId={} from H2 employees table", employeeId);
        List<Employee> employees = jdbcTemplate.query(
                "SELECT employee_id, name, department, address FROM employees WHERE employee_id = ?",
                (rs, rowNum) -> new Employee(
                        rs.getString("employee_id"),
                        rs.getString("name"),
                        rs.getString("department"),
                        rs.getString("address")),
                employeeId);
        log.debug("H2 lookup result for employeeId={} found={}", employeeId, !employees.isEmpty());
        return employees.stream().findFirst();
    }

    @Override
    public List<Employee> findAll() {
        log.debug("Listing employees from H2 employees table");
        List<Employee> employees = jdbcTemplate.query(
                "SELECT employee_id, name, department, address FROM employees ORDER BY employee_id",
                (rs, rowNum) -> new Employee(
                        rs.getString("employee_id"),
                        rs.getString("name"),
                        rs.getString("department"),
                        rs.getString("address")));
        log.info("Listed {} employees from H2 employees table", employees.size());
        return employees;
    }
}
