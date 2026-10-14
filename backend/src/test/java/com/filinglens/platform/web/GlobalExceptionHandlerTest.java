package com.filinglens.platform.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.platform.error.NotFoundException;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
@ActiveProfiles("test")
@WithMockUser
class GlobalExceptionHandlerTest {

	@Autowired
	MockMvc mvc;

	@Test
	void domainExceptionBecomesProblemDetail() throws Exception {
		mvc.perform(get("/test/missing"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:not-found"))
			.andExpect(jsonPath("$.detail").value("Filing '0000000000-00-000000' was not found"))
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void validationErrorsListFields() throws Exception {
		mvc.perform(post("/test/validated").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:validation"))
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void unexpectedErrorsHideInternals() throws Exception {
		mvc.perform(get("/test/crash"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:internal"))
			.andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/test/missing")
		void missing() {
			throw new NotFoundException("Filing", "0000000000-00-000000");
		}

		@PostMapping("/test/validated")
		void validated(@Valid @RequestBody Named body) {
		}

		@GetMapping("/test/crash")
		void crash() {
			throw new IllegalStateException("secret internal detail");
		}

	}

	record Named(@NotBlank String name) {
	}

}
