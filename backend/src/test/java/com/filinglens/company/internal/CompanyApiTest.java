package com.filinglens.company.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.filinglens.company.internal.CompanyViews.CompanySummary;
import com.filinglens.company.internal.CompanyViews.WatchlistEntry;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = { CompanyController.class, WatchlistController.class })
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, RateLimiter.class })
class CompanyApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	static final CompanySummary APPLE = new CompanySummary("0000320193", "Apple Inc.", List.of("AAPL"), "Nasdaq");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CompanyService companies;

	@MockitoBean
	WatchlistService watchlist;

	static RequestPostProcessor user() {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()));
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/companies").param("q", "apple")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/watchlist")).andExpect(status().isUnauthorized());
	}

	@Test
	void searchReturnsSummaries() throws Exception {
		when(companies.search("apple", 10)).thenReturn(List.of(APPLE));

		mvc.perform(get("/api/v1/companies").param("q", "apple").with(user()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].cik").value("0000320193"))
			.andExpect(jsonPath("$[0].tickers[0]").value("AAPL"));
	}

	@Test
	void searchValidatesParameters() throws Exception {
		mvc.perform(get("/api/v1/companies").param("q", "apple").param("limit", "500").with(user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:validation"));
		mvc.perform(get("/api/v1/companies").param("q", " ").with(user())).andExpect(status().isBadRequest());
		verifyNoInteractions(companies);
	}

	@Test
	void invalidCikIsRejected() throws Exception {
		mvc.perform(get("/api/v1/companies/not-a-cik").with(user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("cik"));
	}

	@Test
	void secOutageIsServiceUnavailable() throws Exception {
		when(companies.get("320193", USER.id())).thenThrow(new EdgarUnavailableException("SEC EDGAR is unavailable"));

		mvc.perform(get("/api/v1/companies/320193").with(user()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.type").value("urn:filinglens:problem:edgar-unavailable"));
	}

	@Test
	void addToWatchlistDefaultsToAllFormsAndNotifications() throws Exception {
		when(watchlist.add(eq(USER.id()), eq("320193"), any(), anyBoolean())).thenReturn(
				new WatchlistEntry(5, APPLE, List.of("10-K", "10-Q", "8-K"), true, Instant.parse("2026-06-01T00:00:00Z")));

		mvc.perform(post("/api/v1/watchlist").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"cik\":\"320193\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(5))
			.andExpect(jsonPath("$.notificationsEnabled").value(true));
		verify(watchlist).add(USER.id(), "320193", null, true);
	}

	@Test
	void watchlistRejectsUnknownFormTypes() throws Exception {
		mvc.perform(patch("/api/v1/watchlist/5").with(user())
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"formTypes\":[\"S-1\"],\"notificationsEnabled\":true}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(watchlist);
	}

	@Test
	void removingSomeoneElsesItemIsNotFound() throws Exception {
		org.mockito.Mockito.doThrow(new NotFoundException("Watchlist item", 9L))
			.when(watchlist)
			.remove(eq(USER.id()), anyLong());

		mvc.perform(delete("/api/v1/watchlist/9").with(user()).with(csrf())).andExpect(status().isNotFound());
	}

}
