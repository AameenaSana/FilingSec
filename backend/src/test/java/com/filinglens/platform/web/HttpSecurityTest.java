package com.filinglens.platform.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = SystemController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, HttpSecurityTest.FixedClock.class })
class HttpSecurityTest {

	@Autowired
	MockMvc mvc;

	@Test
	void systemInfoIsPublic() throws Exception {
		mvc.perform(get("/api/v1/system/info"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("FilingLens"))
			.andExpect(jsonPath("$.serverTime").value("2026-01-01T00:00:00Z"));
	}

	@Test
	void everyResponseCarriesARequestId() throws Exception {
		mvc.perform(get("/api/v1/system/info"))
			.andExpect(header().string(RequestCorrelationFilter.HEADER, notNullValue()));
		mvc.perform(get("/api/v1/system/info").header(RequestCorrelationFilter.HEADER, "client-supplied-id-123"))
			.andExpect(header().string(RequestCorrelationFilter.HEADER, "client-supplied-id-123"));
	}

	@Test
	void malformedRequestIdIsReplaced() throws Exception {
		mvc.perform(get("/api/v1/system/info").header(RequestCorrelationFilter.HEADER, "bad\nid"))
			.andExpect(header().string(RequestCorrelationFilter.HEADER, org.hamcrest.Matchers.not("bad\nid")));
	}

	@Test
	void protectedEndpointsReturnProblemDetail401() throws Exception {
		mvc.perform(get("/api/v1/watchlist"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("Content-Type", containsString(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:unauthorized"))
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void stateChangingRequestsWithoutCsrfTokenAreRejected() throws Exception {
		mvc.perform(post("/api/v1/watchlist").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:forbidden"));
	}

	@Test
	void securityHeadersArePresent() throws Exception {
		mvc.perform(get("/api/v1/system/info"))
			.andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClock {

		@Bean
		Clock clock() {
			return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
		}

	}

}
