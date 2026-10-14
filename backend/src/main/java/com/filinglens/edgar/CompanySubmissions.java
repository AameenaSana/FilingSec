package com.filinglens.edgar;

import java.util.List;

/**
 * A company's record from the EDGAR submissions API
 * ({@code data.sec.gov/submissions/CIK##########.json}).
 *
 * @param cik 10-digit CIK
 * @param fiscalYearEnd {@code MMDD}, e.g. {@code 0928}; may be {@code null}
 * @param recentFilings the most recent filings (at least one year, or 1,000), newest
 * first, of all form types
 * @param historyFiles names of additional pages holding older filings, for
 * {@link EdgarClient#fetchSubmissionsHistory(String)}
 */
public record CompanySubmissions(String cik, String name, String entityType, String sic, String sicDescription,
		String category, String fiscalYearEnd, String stateOfIncorporation, List<String> tickers,
		List<String> exchanges, List<FilingRecord> recentFilings, List<String> historyFiles) {

	public CompanySubmissions {
		tickers = tickers == null ? List.of() : List.copyOf(tickers);
		exchanges = exchanges == null ? List.of() : exchanges.stream().filter(e -> e != null && !e.isBlank()).toList();
		recentFilings = recentFilings == null ? List.of() : List.copyOf(recentFilings);
		historyFiles = historyFiles == null ? List.of() : List.copyOf(historyFiles);
	}

}
