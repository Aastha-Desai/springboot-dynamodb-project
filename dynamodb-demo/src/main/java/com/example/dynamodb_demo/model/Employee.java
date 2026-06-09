package com.example.dynamodb_demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Employee payload stored in DynamoDB")
public class Employee {

    @NotBlank
    @Size(max = 20)
    @Schema(description = "Unique employee identifier", example = "EMP-1001", maxLength = 20)
    private String employeeId;

    @NotBlank
    @Schema(description = "Employee name", example = "Asha Patel")
    private String name;

    @NotBlank
    @Schema(description = "Employee department", example = "Engineering")
    private String department;
}
