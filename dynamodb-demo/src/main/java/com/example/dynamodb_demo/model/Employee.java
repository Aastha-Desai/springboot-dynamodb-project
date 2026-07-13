package com.example.dynamodb_demo.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
    @Schema(description = "Employee name. Allows letters, spaces, period, apostrophe, and dash.", example = "Asha Patel")
    private String name;

    @NotBlank
    @Schema(description = "Employee department", example = "Engineering")
    private String department;

    @NotBlank
    @Pattern(regexp = "^[a-zA-Z0-9 ,.-]+$", message = "address contains invalid characters")
    @Schema(description = "Employee address. Allows letters, numbers, spaces, comma, period, and dash.", example = "123 Main St, Newark NJ")
    private String address;
}
