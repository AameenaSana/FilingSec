package com.filinglens.filing.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** API representations of filings. */
final class FilingViews {

	private FilingViews() {
	}

	enum Status {

		DISCOVERED, FETCHING, FETCHED, FAILED

	}

	/**
	 * @param reportDate period of report
	 * @param primaryDocumentUrl the original document on sec.gov
	 */
	record FilingSummary(String accessionNumber, String formType, LocalDate filingDate, LocalDate reportDate,
			Instant acceptanceAt, String description, List<String> items, String primaryDocumentUrl, Status status) {
	}

	/**
	 * @param stale true if EDGAR could not be reached and the list may be missing
	 * recent filings
	 * @param nextCursor opaque cursor for the next page, or {@code null} at the end
	 */
	record FilingPage(List<FilingSummary> items, String nextCursor, boolean stale) {
	}

	record CompanyInfo(String cik, String name) {
	}

	/**
	 * @param stored whether FilingLens holds a copy of this document
	 */
	record FilingDocumentView(int sequence, String filename, String type, String description, Long sizeBytes,
			boolean primary, String sourceUrl, boolean stored, String sha256) {
	}

	record FilingDetail(String accessionNumber, CompanyInfo company, String formType, LocalDate filingDate,
			LocalDate reportDate, Instant acceptanceAt, String description, List<String> items, Long sizeBytes,
			boolean xbrl, boolean inlineXbrl, String fileNumber, Status status, String statusDetail,
			Instant fetchedAt, String filingIndexUrl, String primaryDocumentUrl, List<FilingDocumentView> documents) {

		FilingDetail withDocuments(List<FilingDocumentView> docs) {
			return new FilingDetail(accessionNumber, company, formType, filingDate, reportDate, acceptanceAt,
					description, items, sizeBytes, xbrl, inlineXbrl, fileNumber, status, statusDetail, fetchedAt,
					filingIndexUrl, primaryDocumentUrl, docs);
		}

	}

}
