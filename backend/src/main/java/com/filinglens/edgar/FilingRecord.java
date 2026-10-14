package com.filinglens.edgar;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One filing from the submissions API's filing history.
 *
 * @param accessionNumber dashed form, e.g. {@code 0000320193-25-000079}
 * @param reportDate the period of report; {@code null} for forms without one
 * @param items for 8-Ks, comma-separated item numbers (e.g. {@code 2.02,9.01});
 * otherwise {@code null}
 */
public record FilingRecord(String accessionNumber, String form, LocalDate filingDate, LocalDate reportDate,
		Instant acceptanceDateTime, String primaryDocument, String primaryDocDescription, String items, Long size,
		boolean xbrl, boolean inlineXbrl, String fileNumber, String filmNumber) {
}
