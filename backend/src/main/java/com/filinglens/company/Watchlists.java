package com.filinglens.company;

import java.util.List;
import java.util.UUID;

/** Who watches which companies, for other modules. */
public interface Watchlists {

	/** Companies on at least one user's watchlist. */
	List<CompanyRef> watchedCompanies();

	/** Users watching the company, with their per-company settings. */
	List<Watcher> watchers(long companyId);

	/**
	 * @param formTypes base forms the user follows for this company
	 * @param notificationsEnabled whether the user wants notifications about it
	 */
	record Watcher(UUID userId, List<String> formTypes, boolean notificationsEnabled) {

		public boolean follows(String baseForm) {
			return formTypes.contains(baseForm);
		}

	}

}
