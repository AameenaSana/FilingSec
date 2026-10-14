package com.filinglens.filing;

import java.util.List;

/**
 * Published when refreshing a company's recent filings from SEC finds filings
 * that were not known before. Not published for a company's first sync, whose
 * filings are history rather than news.
 *
 * @param filingIds the new filings, all of tracked form types
 */
public record FilingsDiscovered(long companyId, String cik, List<Long> filingIds) {
}
