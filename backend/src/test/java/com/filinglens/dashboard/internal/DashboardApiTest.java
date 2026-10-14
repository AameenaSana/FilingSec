package com.filinglens.dashboard.internal;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.filinglens.dashboard.internal.DashboardViews.Dashboard;
import com.filinglens.dashboard.internal.DashboardViews.RecentChange;
import com.filinglens.dashboard.internal.DashboardViews.Summary;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.platform.security.ProblemDetailSecurityHandler;
import com.filinglens.platform.security.SecurityConfiguration;

@WebMvcTest(controllers = DashboardController.class)
@ActiveProfiles("test")
@Import({ SecurityConfiguration.class, ProblemDetailSecurityHandler.class, RateLimiter.class })
class DashboardApiTest {

	static final UserPrincipal USER = new UserPrincipal(UUID.randomUUID(), "a@example.com", "USER");

	@Autowired
	MockMvc mvc;

	@MockitoBean
	DashboardService dashboard;

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/v1/dashboard")).andExpect(status().isUnauthorized());
	}

	@Test
	void returnsTheSignedInUsersDashboard() throws Exception {
		when(dashboard.dashboard(USER.id())).thenReturn(new Dashboard(new Summary(1, 2, 3, 4, null), List.of(),
				List.of(), List.of(new RecentChange(7, "Apple Inc.", "AAPL", "10-K", LocalDate.of(2024, 9, 28),
						LocalDate.of(2025, 9, 27), 5, 2, 9, 1, Instant.EPOCH)),
				List.of(), List.of(), List.of()));

		mvc.perform(get("/api/v1/dashboard")
			.with(authentication(UsernamePasswordAuthenticationToken.authenticated(USER, null, USER.authorities()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary.unreadNotifications").value(3))
			.andExpect(jsonPath("$.recentChanges[0].targetPeriod").value("2025-09-27"))
			.andExpect(jsonPath("$.recentChanges[0].modified").value(9));
	}

}
