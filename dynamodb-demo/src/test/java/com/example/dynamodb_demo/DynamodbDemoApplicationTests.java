package com.example.dynamodb_demo;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DynamodbDemoApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void createsAndReadsEmployee() throws Exception {
		mockMvc.perform(post("/api/employees")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "employeeId": "EMP-1001",
								  "name": "Asha Patel",
								  "department": "Engineering"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.employeeId").value("EMP-1001"))
				.andExpect(jsonPath("$.name").value("Asha Patel"))
				.andExpect(jsonPath("$.department").value("Engineering"));

		mockMvc.perform(get("/api/employees/EMP-1001"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.employeeId").value("EMP-1001"));
	}

	@Test
	void rejectsEmployeeIdLongerThanTwentyCharacters() throws Exception {
		mockMvc.perform(post("/api/employees")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "employeeId": "EMPLOYEE-ID-IS-WAY-TOO-LONG",
								  "name": "Asha Patel",
								  "department": "Engineering"
								}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsMissingRequiredFields() throws Exception {
		mockMvc.perform(post("/api/employees")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "employeeId": "EMP-1002"
								}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void listsEmployeesFromH2Repository() throws Exception {
		mockMvc.perform(post("/api/employees")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "employeeId": "EMP-1003",
								  "name": "Maya Shah",
								  "department": "QA"
								}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/employees"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.employeeId == 'EMP-1003')]", hasSize(1)));
	}

}
