package com.filinglens.comparison;

/** Published when the differences between two filings have been computed. */
public record ComparisonCompleted(long comparisonId, long baseFilingId, long targetFilingId) {
}
