package com.filinglens.workspace.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** API representations of research sessions and findings. */
final class WorkspaceViews {

	private WorkspaceViews() {
	}

	record SessionSummary(long id, String title, String description, int findingCount, Instant updatedAt) {
	}

	record SessionView(long id, String title, String description, List<FindingView> findings, Instant createdAt,
			Instant updatedAt) {
	}

	record FilingRef(String accessionNumber, String cik, String companyName, String formType, LocalDate reportDate) {
	}

	/**
	 * @param sourceType CITATION, CHANGE or PASSAGE
	 * @param blockOrdinal the viewer anchor of the quoted passage
	 * @param previousText for a modified change, the earlier filing's text
	 */
	record FindingView(long id, Long sessionId, String sourceType, FilingRef filing, String sectionKey,
			String sectionTitle, List<String> blockRefs, int blockOrdinal, String quotedText, String previousText,
			String previousAccession, Integer previousBlockOrdinal, String changeType, Long comparisonId,
			Integer changeOrdinal, Long conversationId, String note, List<String> tags, Instant createdAt,
			Instant updatedAt) {
	}

}
