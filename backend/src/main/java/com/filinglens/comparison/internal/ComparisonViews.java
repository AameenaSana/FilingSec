package com.filinglens.comparison.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** API representations of comparisons. */
final class ComparisonViews {

	private ComparisonViews() {
	}

	enum Status {

		/** Waiting for one or both documents to be downloaded and parsed. */
		WAITING, COMPUTING, READY, FAILED

	}

	record FilingInfo(String accessionNumber, String formType, LocalDate filingDate, LocalDate reportDate) {
	}

	record CompanyInfo(String cik, String name) {
	}

	record Stats(int added, int removed, int modified, int moved, int unchanged, int numericOnly) {
	}

	record SectionSummary(String key, String title, String alignment, int added, int removed, int modified, int moved,
			int unchanged, int numericOnly) {
	}

	/**
	 * @param base the earlier filing
	 * @param target the later filing
	 */
	record ComparisonView(long id, Status status, String detail, CompanyInfo company, FilingInfo base,
			FilingInfo target, Stats stats, Instant computedAt, List<SectionSummary> sections) {
	}

	/** A block on one side of a change, with what is needed to locate it in the source. */
	record BlockSide(int ordinal, String ref, String text) {
	}

	/**
	 * @param base the block in the earlier filing; {@code null} for ADDED
	 * @param target the block in the later filing; {@code null} for REMOVED
	 * @param diff word-level differences for MODIFIED changes
	 */
	record ChangeView(int ordinal, String type, boolean numericOnly, Double similarity, BlockSide base,
			BlockSide target, List<WordDiff.Op> diff) {
	}

	/** @param suggested the filing most naturally compared with, if any */
	record Candidates(String suggested, List<FilingInfo> filings) {
	}

}
