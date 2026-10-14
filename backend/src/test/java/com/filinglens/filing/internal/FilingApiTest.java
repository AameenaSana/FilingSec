package com.filinglens.filing.internal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.filinglens.filing.internal.FilingViews.CompanyInfo;
import com.filinglens.filing.internal.FilingViews.FilingDetail;
import com.filinglens.filing.internal.FilingViews.FilingPage;
import com.filinglens.filing.internal.FilingViews.Status;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = FilingController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, RateLimiter.class })
class FilingApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	FilingDiscoveryService discovery;

	@MockitoBean
	FilingService filings;

	static RequestPostProcessor user() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()));
	}

	@Test
	void listsFilingsWithFormFilter() throws Exception {
		when(discovery.list("320193", List.of("10-K", "8-K"), null, 25)).thenReturn(new FilingPage(List.of(), null, false));

		mvc.perform(get("/api/v1/companies/320193/filings").param("forms", "10-K", "8-K").with(user()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stale").value(false));
		verify(discovery).list("320193", List.of("10-K", "8-K"), null, 25);
	}

	@Test
	void rejectsUnsupportedFormsAndBadAccessions() throws Exception {
		mvc.perform(get("/api/v1/companies/320193/filings").param("forms", "S-1").with(user()))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/filings/not-an-accession").with(user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:validation"));
		verifyNoInteractions(discovery, filings);
	}

	@Test
	void unknownFilingIsNotFound() throws Exception {
		when(filings.get("0000320193-25-000079")).thenThrow(new NotFoundException("Filing", "0000320193-25-000079"));

		mvc.perform(get("/api/v1/filings/0000320193-25-000079").with(user())).andExpect(status().isNotFound());
	}

	@Test
	void fetchIsAcceptedAndRequiresCsrf() throws Exception {
		when(filings.requestFetch(eq("0000320193-25-000079"))).thenReturn(new FilingDetail("0000320193-25-000079",
				new CompanyInfo("0000320193", "Apple Inc."), "10-K", LocalDate.of(2025, 10, 31), null, null, null,
				List.of(), null, true, true, null, Status.FETCHING, null, null, "https://www.sec.gov/x", null,
				List.of()));

		mvc.perform(post("/api/v1/filings/0000320193-25-000079/fetch").with(user())).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/filings/0000320193-25-000079/fetch").with(user()).with(csrf()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("FETCHING"));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/filings/0000320193-25-000079")).andExpect(status().isUnauthorized());
		verifyNoInteractions(filings);
		verifyNoInteractions(discovery);
	}

}
