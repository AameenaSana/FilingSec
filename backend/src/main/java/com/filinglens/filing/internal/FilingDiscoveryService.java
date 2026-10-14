package com.filinglens.filing.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.filinglens.company.CompanyDirectory;
import com.filinglens.company.CompanyRef;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.edgar.FilingRecord;
import com.filinglens.filing.internal.FilingRepository.Cursor;
import com.filinglens.filing.internal.FilingRepository.SyncState;
import com.filinglens.filing.internal.FilingViews.FilingPage;
import com.filinglens.filing.internal.FilingViews.FilingSummary;
import com.filinglens.platform.error.InvalidRequestException;
import com.filinglens.platform.jobs.JobQueue;

/**
 * Discovers a company's filings. Recent filings are read from EDGAR's submissions
 * API when the stored list is older than the configured maximum age; older
 * filings live on additional history pages, which are imported once in the
 * background.
 */
@Service
class FilingDiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(FilingDiscoveryService.class);

	private final CompanyDirectory companies;

	private final EdgarClient edgar;

	private final FilingRepository filings;

	private final JobQueue jobs;

	private final TransactionTemplate transactions;

	private final FilingProperties properties;

	private final Clock clock;

	FilingDiscoveryService(CompanyDirectory companies, EdgarClient edgar, FilingRepository filings, JobQueue jobs,
			TransactionTemplate transactions, FilingProperties properties, Clock clock) {
		this.companies = companies;
		this.edgar = edgar;
		this.filings = filings;
		this.jobs = jobs;
		this.transactions = transactions;
		this.properties = properties;
		this.clock = clock;
	}

	FilingPage list(String cik, List<String> baseForms, String cursor, int limit) {
		CompanyRef company = companies.resolve(cik);
		boolean stale = !refreshIfStale(company);
		Cursor after = cursor == null ? null
				: filings.cursorOf(CursorCodec.decode(cursor))
					.orElseThrow(() -> new InvalidRequestException("invalid-cursor", "The page cursor is not valid"));
		List<FilingSummary> page = filings.page(company.id(), company.cik(), FormTypes.withAmendments(baseForms), after,
				limit + 1);
		String next = page.size() > limit ? CursorCodec.encode(page.get(limit - 1).accessionNumber()) : null;
		return new FilingPage(page.subList(0, Math.min(limit, page.size())), next, stale);
	}

	/**
	 * Reads recent filings from EDGAR if the stored list is older than the maximum
	 * age. If EDGAR is unavailable but filings were synced before, the stored list
	 * is used.
	 * @return false if the stored list could not be refreshed
	 */
	boolean refreshIfStale(CompanyRef company) {
		SyncState state = filings.syncState(company.id());
		Instant now = clock.instant();
		if (state.recentSyncedAt() != null && state.recentSyncedAt().plus(properties.listMaxAge()).isAfter(now)) {
			return true;
		}
		try {
			refresh(company);
			return true;
		}
		catch (EdgarUnavailableException ex) {
			if (state.recentSyncedAt() == null) {
				throw ex;
			}
			log.warn("Serving stored filings for CIK {}: {}", company.cik(), ex.getMessage());
			return false;
		}
	}

	/** Reads recent filings from EDGAR now and queues import of any unimported history pages. */
	void refresh(CompanyRef company) {
		CompanySubmissions submissions = edgar.fetchSubmissions(company.cik()).orElse(null);
		Instant now = clock.instant();
		transactions.executeWithoutResult(status -> {
			if (submissions != null) {
				int added = filings.insertNew(company.id(), tracked(submissions.recentFilings()), now);
				if (added > 0) {
					log.info("Discovered {} new filings for CIK {}", added, company.cik());
				}
			}
			filings.markRecentSynced(company.id(), now);
		});
		if (submissions == null) {
			return;
		}
		Set<String> imported = filings.syncState(company.id()).importedHistoryFiles();
		List<String> pending = submissions.historyFiles().stream().filter(f -> !imported.contains(f)).toList();
		if (!pending.isEmpty()) {
			jobs.enqueueUnique(FilingHistoryImportJob.TYPE,
					Map.of("companyId", company.id(), "cik", company.cik(), "files", pending),
					FilingHistoryImportJob.TYPE + ":" + company.id());
		}
	}

	static List<FilingRecord> tracked(List<FilingRecord> filings) {
		return filings.stream().filter(f -> FormTypes.isTracked(f.form())).toList();
	}

}
