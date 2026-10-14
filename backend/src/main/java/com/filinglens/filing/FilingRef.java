package com.filinglens.filing;

import java.time.LocalDate;

/**
 * A filing as referenced by other modules.
 *
 * @param cik the filer's 10-digit CIK
 * @param reportDate period of report; may be {@code null}
 * @param primaryDocument the primary document's file name; may be {@code null}
 * @param primaryDocumentBlobKey blob-store key of the stored primary document, or
 * {@code null} if it has not been fetched
 * @param fetchStatus DISCOVERED, FETCHING, FETCHED or FAILED
 */
public record FilingRef(long id, long companyId, String accessionNumber, String cik, String companyName,
		String formType, LocalDate filingDate, LocalDate reportDate, String primaryDocument,
		String primaryDocumentBlobKey, String fetchStatus) {

	public boolean isFetched() {
		return primaryDocumentBlobKey != null;
	}

	public boolean fetchFailed() {
		return "FAILED".equals(fetchStatus);
	}

	/** The form without an amendment suffix, e.g. {@code 10-K} for {@code 10-K/A}. */
	public String baseForm() {
		return formType.endsWith("/A") ? formType.substring(0, formType.length() - 2) : formType;
	}

}
