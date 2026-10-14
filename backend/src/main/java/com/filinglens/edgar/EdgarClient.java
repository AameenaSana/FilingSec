package com.filinglens.edgar;

import java.util.List;
import java.util.Optional;

/** Typed access to the official SEC EDGAR endpoints used by FilingLens. */
public interface EdgarClient {

	/**
	 * The full ticker directory: every company with an exchange-listed or OTC
	 * ticker. A company with several share classes appears once per ticker, in
	 * SEC's order (primary listing first).
	 */
	List<CompanyTickerEntry> fetchCompanyTickers();

	/** @return empty if SEC has no company with this CIK */
	Optional<CompanySubmissions> fetchSubmissions(String cik);

	/**
	 * Older filings from one of the pages listed in
	 * {@link CompanySubmissions#historyFiles()}.
	 */
	List<FilingRecord> fetchSubmissionsHistory(String fileName);

	/**
	 * The documents of a filing, from its index page.
	 * @return empty if the filing does not exist
	 */
	Optional<List<FilingIndexDocument>> fetchFilingIndex(String cik, String accession);

	/**
	 * The original bytes of a document in a filing's archive folder.
	 * @return empty if the document does not exist
	 */
	Optional<byte[]> fetchArchiveDocument(String cik, String accession, String filename);

}
