package com.filinglens.filing;

import java.util.List;
import java.util.Optional;

/** Filing lookups and document requests for other modules. */
public interface FilingCatalog {

	/** @param accessionNumber dashed or undashed form */
	Optional<FilingRef> findByAccession(String accessionNumber);

	Optional<FilingRef> findById(long filingId);

	/**
	 * Other filings of the same company and base form (amendments included), newest
	 * period first.
	 */
	List<FilingRef> comparableFilings(long filingId, int limit);

	/**
	 * The company's most recent original (non-amendment) filing of a base form,
	 * refreshing the company's filing list from SEC first if it is stale.
	 * @param fiscalYear restricts to filings whose period of report ends in that
	 * calendar year; {@code null} for the latest
	 */
	Optional<FilingRef> latestFiling(String cik, String baseForm, Integer fiscalYear);

	/**
	 * Re-reads the company's recent filings from SEC now, publishing
	 * {@link FilingsDiscovered} for any new ones.
	 * @throws com.filinglens.edgar.EdgarUnavailableException if SEC is unavailable
	 */
	void refreshRecent(String cik);

	/** Queues download of the filing's documents unless already fetched or in progress. */
	void requestDocuments(long filingId);

}
