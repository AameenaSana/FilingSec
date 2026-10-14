package com.filinglens.edgar;

import java.util.List;

/**
 * Company metadata from the EDGAR submissions API
 * ({@code data.sec.gov/submissions/CIK##########.json}). Filing history is added
 * to this type when filing discovery is implemented.
 *
 * @param cik 10-digit CIK
 * @param fiscalYearEnd {@code MMDD}, e.g. {@code 0928}; may be {@code null}
 */
public record CompanySubmissions(String cik, String name, String entityType, String sic, String sicDescription,
		String category, String fiscalYearEnd, String stateOfIncorporation, List<String> tickers,
		List<String> exchanges) {

	public CompanySubmissions {
		tickers = tickers == null ? List.of() : List.copyOf(tickers);
		exchanges = exchanges == null ? List.of() : exchanges.stream().filter(e -> e != null && !e.isBlank()).toList();
	}

}
