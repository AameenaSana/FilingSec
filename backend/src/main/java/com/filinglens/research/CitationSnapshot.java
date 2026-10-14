package com.filinglens.research;

import java.time.LocalDate;
import java.util.List;

/** A citation from an AI answer, with everything needed to show and locate its source. */
public record CitationSnapshot(long citationId, long conversationId, long filingId, String accessionNumber,
		String companyName, String formType, LocalDate reportDate, String sectionKey, String sectionTitle,
		List<String> blockRefs, int firstBlockOrdinal, String citedText) {
}
