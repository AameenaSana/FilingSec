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

}
