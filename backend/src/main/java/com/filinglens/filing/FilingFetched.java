package com.filinglens.filing;

/**
 * Published when a filing's primary document has been downloaded from SEC and
 * stored.
 *
 * @param primaryDocumentBlobKey blob-store key of the original primary document
 */
public record FilingFetched(long filingId, String accessionNumber, String primaryDocumentBlobKey) {
}
