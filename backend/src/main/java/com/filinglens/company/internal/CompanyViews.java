package com.filinglens.company.internal;

import java.time.Instant;
import java.util.List;

/** API representations of companies and watchlist entries. */
final class CompanyViews {

	private CompanyViews() {
	}

	/** A search result or watchlist company. {@code exchange} is the primary listing's. */
	record CompanySummary(String cik, String name, List<String> tickers, String exchange) {
	}

	/**
	 * @param watchlistItemId the current user's watchlist entry for this company, if any
	 * @param profileSyncedAt when details were last refreshed from EDGAR; {@code null}
	 * if EDGAR has not been reachable yet
	 */
	record CompanyDetail(String cik, String name, List<String> tickers, String exchange, String entityType,
			String sic, String sicDescription, String category, String fiscalYearEnd, String stateOfIncorporation,
			String edgarUrl, Instant profileSyncedAt, Long watchlistItemId) {
	}

	record WatchlistEntry(long id, CompanySummary company, List<String> formTypes, boolean notificationsEnabled,
			Instant createdAt) {
	}

	static String edgarUrl(String cik) {
		return "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik;
	}

}
