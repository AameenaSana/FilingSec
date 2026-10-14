package com.filinglens.comparison;

import java.time.LocalDate;

/**
 * A detected change between two filings. Base fields describe the earlier filing
 * and are null for ADDED; target fields describe the later filing and are null for
 * REMOVED.
 */
public record ChangeSnapshot(long comparisonId, int changeOrdinal, String changeType, String sectionKey,
		String sectionTitle, String companyName, String formType, Side base, Side target) {

	public record Side(long filingId, String accessionNumber, LocalDate reportDate, String blockRef, int blockOrdinal,
			String text) {
	}

}
