package com.filinglens.dashboard.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The dashboard's API representation. */
final class DashboardViews {

	private DashboardViews() {
	}

	/**
	 * @param recentFilings filings of watched companies in the forms the user follows,
	 * filed within {@link DashboardRepository#RECENT_DAYS} days
	 * @param recentChanges completed comparisons of watched companies' filings
	 */
	record Dashboard(Summary summary, List<WatchedCompany> companies, List<RecentFiling> recentFilings,
			List<RecentChange> recentChanges, List<NotificationItem> notifications, List<ConversationItem> conversations,
			List<SessionItem> sessions) {
	}

	/** @param lastCheckedAt when SEC was last successfully checked for any watched company */
	record Summary(int watchedCompanies, int filingsLast30Days, int unreadNotifications, int findings,
			Instant lastCheckedAt) {
	}

	record FilingInfo(String accessionNumber, String formType, LocalDate filingDate, LocalDate reportDate) {
	}

	record WatchedCompany(String cik, String name, String ticker, List<String> formTypes, FilingInfo latestFiling,
			int unreadNotifications, Instant lastCheckedAt) {
	}

	/** @param items 8-K item numbers, e.g. "2.02,9.01" */
	record RecentFiling(String cik, String companyName, String ticker, String accessionNumber, String formType,
			LocalDate filingDate, LocalDate reportDate, String items) {
	}

	record RecentChange(long comparisonId, String companyName, String ticker, String formType, LocalDate basePeriod,
			LocalDate targetPeriod, int added, int removed, int modified, int moved, Instant computedAt) {
	}

	record NotificationItem(long id, String type, String title, String link, Instant createdAt, boolean read) {
	}

	record ConversationItem(long id, String title, Instant updatedAt) {
	}

	record SessionItem(long id, String title, int findingCount, Instant updatedAt) {
	}

}
