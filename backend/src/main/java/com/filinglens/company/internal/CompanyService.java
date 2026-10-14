package com.filinglens.company.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.filinglens.company.internal.CompanyRepository.CompanyRow;
import com.filinglens.company.internal.CompanyViews.CompanyDetail;
import com.filinglens.company.internal.CompanyViews.CompanySummary;
import com.filinglens.edgar.Cik;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.platform.error.NotFoundException;

/**
 * Company search and profiles. Profiles are loaded from EDGAR on first view and
 * refreshed when older than the configured maximum age; if EDGAR is unavailable,
 * the stored profile is served.
 */
@Service
class CompanyService {

	private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

	private final CompanyRepository companies;

	private final EdgarClient edgar;

	private final CompanyProperties properties;

	private final Clock clock;

	CompanyService(CompanyRepository companies, EdgarClient edgar, CompanyProperties properties, Clock clock) {
		this.companies = companies;
		this.edgar = edgar;
		this.properties = properties;
		this.clock = clock;
	}

	List<CompanySummary> search(String query, int limit) {
		return companies.search(query, limit);
	}

	CompanyDetail get(String cik, UUID userId) {
		String normalized = Cik.normalize(cik);
		ensureFresh(normalized);
		return companies.findDetail(normalized, userId).orElseThrow(() -> new NotFoundException("Company", cik));
	}

	/**
	 * Returns the company's id, loading it from EDGAR if it is not known yet (e.g. a
	 * company without a listed ticker).
	 */
	long resolveId(String cik) {
		String normalized = Cik.normalize(cik);
		return ensureFresh(normalized);
	}

	private long ensureFresh(String cik) {
		Optional<CompanyRow> existing = companies.findByCik(cik);
		Instant now = clock.instant();
		if (existing.isPresent() && !isStale(existing.get(), now)) {
			return existing.get().id();
		}
		Optional<CompanySubmissions> profile;
		try {
			profile = edgar.fetchSubmissions(cik);
		}
		catch (EdgarUnavailableException ex) {
			if (existing.isEmpty()) {
				throw ex;
			}
			log.warn("Serving stored profile for CIK {}: {}", cik, ex.getMessage());
			return existing.get().id();
		}
		if (profile.isEmpty()) {
			return existing.map(CompanyRow::id).orElseThrow(() -> new NotFoundException("Company", cik));
		}
		return companies.upsertProfile(profile.get(), now);
	}

	private boolean isStale(CompanyRow row, Instant now) {
		return row.profileSyncedAt() == null || row.profileSyncedAt().plus(properties.profileMaxAge()).isBefore(now);
	}

}
