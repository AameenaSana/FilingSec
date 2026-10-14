package com.filinglens.comparison.internal;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.filinglens.comparison.internal.ComparisonViews.BlockSide;
import com.filinglens.comparison.internal.ComparisonViews.ChangeView;
import com.filinglens.comparison.internal.ComparisonViews.CompanyInfo;
import com.filinglens.comparison.internal.ComparisonViews.ComparisonView;
import com.filinglens.comparison.internal.ComparisonViews.FilingInfo;
import com.filinglens.comparison.internal.ComparisonViews.Status;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = ComparisonController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, RateLimiter.class })
class ComparisonApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ComparisonService comparisons;

	static RequestPostProcessor user() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()));
	}

	@Test
	void createsComparison() throws Exception {
		when(comparisons.create("0000320193-24-000123", "0000320193-25-000079")).thenReturn(new ComparisonView(9,
				Status.WAITING, null, new CompanyInfo("0000320193", "Apple Inc."),
				new FilingInfo("0000320193-24-000123", "10-K", LocalDate.of(2024, 11, 1), LocalDate.of(2024, 9, 28)),
				new FilingInfo("0000320193-25-000079", "10-K", LocalDate.of(2025, 10, 31), LocalDate.of(2025, 9, 27)),
				null, null, List.of()));

		mvc.perform(post("/api/v1/comparisons").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"base\":\"0000320193-24-000123\",\"target\":\"0000320193-25-000079\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(9))
			.andExpect(jsonPath("$.status").value("WAITING"))
			.andExpect(jsonPath("$.target.reportDate").value("2025-09-27"));
	}

	@Test
	void validatesInput() throws Exception {
		mvc.perform(post("/api/v1/comparisons").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"base\":\"x\",\"target\":\"0000320193-25-000079\"}")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/comparisons/9/sections/{key}/changes", "lower case").with(user()))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(comparisons);
	}

	@Test
	void returnsChangesWithSourceLocations() throws Exception {
		when(comparisons.changes(9, "10K.ITEM_1A", false)).thenReturn(List.of(new ChangeView(1, "MODIFIED", false, 0.8,
				new BlockSide(40, "10K.ITEM_1A/p3", "old"), new BlockSide(42, "10K.ITEM_1A/p3", "new"),
				List.of(new WordDiff.Op("delete", "old"), new WordDiff.Op("insert", "new")))));

		mvc.perform(get("/api/v1/comparisons/9/sections/10K.ITEM_1A/changes").param("includeNumericOnly", "false")
			.with(user()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].base.ordinal").value(40))
			.andExpect(jsonPath("$[0].target.ref").value("10K.ITEM_1A/p3"))
			.andExpect(jsonPath("$[0].diff[1].op").value("insert"));
	}

	@Test
	void unknownComparisonIsNotFound() throws Exception {
		when(comparisons.get(404)).thenThrow(new NotFoundException("Comparison", 404));

		mvc.perform(get("/api/v1/comparisons/404").with(user())).andExpect(status().isNotFound());
	}

}
