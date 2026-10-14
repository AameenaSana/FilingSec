package com.filinglens.dashboard.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filinglens.dashboard.internal.DashboardViews.Dashboard;
import com.filinglens.dashboard.internal.DashboardViews.Summary;
import com.filinglens.dashboard.internal.DashboardViews.WatchedCompany;

@Service
class DashboardService {

	private final DashboardRepository dashboard;

	private final Clock clock;

	DashboardService(DashboardRepository dashboard, Clock clock) {
		this.dashboard = dashboard;
		this.clock = clock;
	}

	/** Read in one transaction so the counts and lists are consistent. */
	@Transactional(readOnly = true)
	Dashboard dashboard(UUID userId) {
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		List<WatchedCompany> companies = dashboard.companies(userId);
		Instant lastChecked = companies.stream()
			.map(WatchedCompany::lastCheckedAt)
			.filter(Objects::nonNull)
			.max(Comparator.naturalOrder())
			.orElse(null);
		Summary summary = new Summary(companies.size(), dashboard.filingsSince(userId, today.minusDays(30)),
				dashboard.unreadNotifications(userId), dashboard.findings(userId), lastChecked);
		return new Dashboard(summary, companies,
				dashboard.recentFilings(userId, today.minusDays(DashboardRepository.RECENT_DAYS), 15),
				dashboard.recentChanges(userId, 6), dashboard.notifications(userId, 5),
				dashboard.conversations(userId, 5), dashboard.sessions(userId, 5));
	}

}
