
package com.example.dynamodb_demo.service;

import com.example.dynamodb_demo.model.Employee;
import org.springframework.stereotype.Service;

@Service
public class EmployeeService {


    public void saveEmployee(Employee employee) {
        System.out.println("Saving employee: " + employee);
    }

}
