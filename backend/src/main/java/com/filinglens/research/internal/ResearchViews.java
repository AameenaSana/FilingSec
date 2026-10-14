package com.filinglens.research.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** API representations of research conversations. */
final class ResearchViews {

	private ResearchViews() {
	}

	record FilingInfo(String accessionNumber, String cik, String companyName, String formType, LocalDate reportDate,
			LocalDate filingDate) {
	}

	/**
	 * @param status PREPARING (being downloaded, parsed or indexed), READY or
	 * UNAVAILABLE (the document cannot be read)
	 * @param semanticSearch whether the filing has embeddings; otherwise keyword search only
	 */
	record FilingReadiness(String accessionNumber, String status, boolean semanticSearch) {
	}

	record ConversationSummary(long id, String title, List<FilingInfo> filings, Instant updatedAt) {
	}

	/**
	 * @param ordinal marker number in the answer ([1], [2], ...)
	 * @param firstBlockOrdinal the viewer anchor of the first cited block
	 * @param answerStart start of the answer text this citation supports
	 * @param verified whether the cited text was found verbatim in the stored source
	 */
	record CitationView(int ordinal, FilingInfo filing, String sectionKey, String sectionTitle, List<String> blockRefs,
			int firstBlockOrdinal, String citedText, int answerStart, int answerEnd, boolean verified) {
	}

	/**
	 * @param status COMPLETE, STREAMING, REFUSED or FAILED
	 */
	record MessageView(long id, String role, String content, String status, Instant createdAt,
			List<CitationView> citations) {
	}

	/**
	 * @param aiAvailable false when no LLM is configured on the server
	 */
	record ConversationView(long id, String title, List<FilingInfo> filings, List<FilingReadiness> readiness,
			List<MessageView> messages, boolean aiAvailable, Instant updatedAt) {
	}

}
