package com.filinglens.dashboard.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.filinglens.dashboard.internal.DashboardViews.WatchedCompany;

class DashboardServiceTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final UUID USER = UUID.randomUUID();

	DashboardRepository repository = mock(DashboardRepository.class);

	DashboardService service = new DashboardService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void summarizesCountsAndTheLatestSecCheck() {
		Instant earlier = NOW.minusSeconds(3600);
		Instant later = NOW.minusSeconds(60);
		when(repository.companies(USER)).thenReturn(List.of(company(earlier), company(null), company(later)));
		when(repository.filingsSince(USER, LocalDate.of(2026, 5, 2))).thenReturn(4);
		when(repository.unreadNotifications(USER)).thenReturn(2);
		when(repository.findings(USER)).thenReturn(7);

		var dashboard = service.dashboard(USER);

		assertThat(dashboard.summary()).extracting(s -> s.watchedCompanies(), s -> s.filingsLast30Days(),
				s -> s.unreadNotifications(), s -> s.findings(), s -> s.lastCheckedAt())
			.containsExactly(3, 4, 2, 7, later);
		verify(repository).recentFilings(eq(USER), eq(LocalDate.of(2026, 3, 3)), anyInt());
	}

	@Test
	void emptyWatchlistHasNoLastCheck() {
		when(repository.companies(any())).thenReturn(List.of());

		assertThat(service.dashboard(USER).summary().lastCheckedAt()).isNull();
	}

	static WatchedCompany company(Instant checked) {
		return new WatchedCompany("0000320193", "Apple Inc.", "AAPL", List.of("10-K"), null, 0, checked);
	}

}
