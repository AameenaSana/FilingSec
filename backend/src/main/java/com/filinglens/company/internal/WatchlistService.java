package com.filinglens.company.internal;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filinglens.company.internal.CompanyViews.WatchlistEntry;
import com.filinglens.platform.error.ConflictException;
import com.filinglens.platform.error.NotFoundException;

@Service
class WatchlistService {

	/** The filing forms FilingLens tracks, in display order. */
	static final List<String> FORM_TYPES = List.of("10-K", "10-Q", "8-K");

	static final int MAX_ITEMS = 200;

	private final WatchlistRepository watchlist;

	private final CompanyService companies;

	private final Clock clock;

	WatchlistService(WatchlistRepository watchlist, CompanyService companies, Clock clock) {
		this.watchlist = watchlist;
		this.companies = companies;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	List<WatchlistEntry> list(UUID userId) {
		return watchlist.findAll(userId);
	}

	/** Not transactional: resolving an unknown company may call SEC. */
	WatchlistEntry add(UUID userId, String cik, Collection<String> formTypes, boolean notify) {
		if (watchlist.count(userId) >= MAX_ITEMS) {
			throw new ConflictException("watchlist-full", "A watchlist can hold at most %d companies".formatted(MAX_ITEMS));
		}
		long companyId = companies.resolveId(cik);
		long id = watchlist.insert(userId, companyId, canonical(formTypes), notify, clock.instant())
			.orElseThrow(() -> new ConflictException("already-watching", "This company is already on your watchlist"));
		return watchlist.find(userId, id).orElseThrow();
	}

	@Transactional
	WatchlistEntry update(UUID userId, long id, Collection<String> formTypes, boolean notify) {
		if (!watchlist.update(userId, id, canonical(formTypes), notify, clock.instant())) {
			throw new NotFoundException("Watchlist item", id);
		}
		return watchlist.find(userId, id).orElseThrow();
	}

	@Transactional
	void remove(UUID userId, long id) {
		if (!watchlist.delete(userId, id)) {
			throw new NotFoundException("Watchlist item", id);
		}
	}

	/** De-duplicated, in canonical order; all forms when none are given. */
	static List<String> canonical(Collection<String> formTypes) {
		if (formTypes == null || formTypes.isEmpty()) {
			return FORM_TYPES;
		}
		return FORM_TYPES.stream().filter(formTypes::contains).toList();
	}

}
