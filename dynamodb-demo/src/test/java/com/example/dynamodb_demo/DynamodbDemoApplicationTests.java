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
								  "department": "Engineering",
								  "address": "123 Main St, Newark NJ"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.employeeId").value("EMP-1001"))
				.andExpect(jsonPath("$.name").value("Asha Patel"))
				.andExpect(jsonPath("$.department").value("Engineering"))
				.andExpect(jsonPath("$.address").value("123 Main St, Newark NJ"));

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
								  "department": "Engineering",
								  "address": "123 Main St, Newark NJ"
								}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAddressWithUnsupportedSpecialCharacters() throws Exception {
		for (String address : java.util.List.of("New_Jersey", "New:Jersey", "New;Jersey")) {
			mockMvc.perform(post("/api/employees")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{
									  "employeeId": "EMP-ADDR",
									  "name": "Asha Patel",
									  "department": "Engineering",
									  "address": "%s"
									}
									""".formatted(address)))
					.andExpect(status().isBadRequest());
		}
	}

	@Test
	void rejectsNameWithUnsupportedSpecialCharacters() throws Exception {
		for (String name : java.util.List.of("Asha_123", "Asha:Patel", "Asha;Patel")) {
			mockMvc.perform(post("/api/employees")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{
									  "employeeId": "EMP-NAME",
									  "name": "%s",
									  "department": "Engineering",
									  "address": "123 Main St, Newark NJ"
									}
									""".formatted(name)))
					.andExpect(status().isBadRequest());
		}
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
								  "department": "QA",
								  "address": "45 Market St, New Jersey"
								}
								"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/employees"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.employeeId == 'EMP-1003')]", hasSize(1)));
	}

}
