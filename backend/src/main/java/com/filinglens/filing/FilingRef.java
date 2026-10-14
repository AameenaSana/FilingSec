package com.filinglens.filing;

/**
 * A filing as referenced by other modules.
 *
 * @param cik the filer's 10-digit CIK
 * @param primaryDocument the primary document's file name; may be {@code null}
 * @param primaryDocumentBlobKey blob-store key of the stored primary document, or
 * {@code null} if it has not been fetched
 */
public record FilingRef(long id, String accessionNumber, String cik, String companyName, String formType,
		String primaryDocument, String primaryDocumentBlobKey) {

	public boolean isFetched() {
		return primaryDocumentBlobKey != null;
	}

}
