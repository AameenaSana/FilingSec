package com.filinglens.filing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.edgar.FilingIndexDocument;
import com.filinglens.edgar.FilingRecord;
import com.filinglens.filing.internal.FilingRepository.Cursor;
import com.filinglens.filing.internal.FilingViews.FilingSummary;
import com.filinglens.filing.internal.FilingViews.Status;
import com.filinglens.platform.storage.BlobStore.StoredBlob;

@IntegrationTest
class FilingIntegrationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Autowired
	FilingRepository filings;

	@Autowired
	JdbcClient jdbc;

	long companyId;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0000320193', 'Apple Inc.', now(), now())
				RETURNING id
				""").query(Long.class).single();
	}

	@Test
	void insertsNewFilingsIdempotentlyAndPagesNewestFirst() {
		List<FilingRecord> records = List.of(record("0000320193-25-000079", "10-K", "2025-10-31"),
				record("0000320193-25-000071", "8-K", "2025-08-01"),
				record("0000320193-25-000057", "10-Q", "2025-07-31"),
				record("0000320193-24-000123", "10-K/A", "2024-11-01"));

		assertThat(filings.insertNew(companyId, records, NOW)).isEqualTo(4);
		assertThat(filings.insertNew(companyId, records, NOW)).isZero();

		List<FilingSummary> first = filings.page(companyId, "0000320193", FormTypes.withAmendments(null), null, 2);
		assertThat(first).extracting(FilingSummary::accessionNumber)
			.containsExactly("0000320193-25-000079", "0000320193-25-000071");
		Cursor cursor = filings.cursorOf("0000320193-25-000071").orElseThrow();
		assertThat(filings.page(companyId, "0000320193", FormTypes.withAmendments(null), cursor, 10))
			.extracting(FilingSummary::accessionNumber)
			.containsExactly("0000320193-25-000057", "0000320193-24-000123");

		assertThat(filings.page(companyId, "0000320193", FormTypes.withAmendments(List.of("10-K")), null, 10))
			.extracting(FilingSummary::formType)
			.containsExactly("10-K", "10-K/A");
		assertThat(first.getFirst().primaryDocumentUrl())
			.isEqualTo("https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/doc.htm");
	}

	@Test
	void fetchLifecycleAndDocuments() {
		filings.insertNew(companyId, List.of(record("0000320193-25-000079", "10-K", "2025-10-31")), NOW);

		assertThat(filings.markFetching("0000320193-25-000079", NOW)).isTrue();
		assertThat(filings.markFetching("0000320193-25-000079", NOW)).as("already fetching").isFalse();

		var target = filings.findFetchTarget("0000320193-25-000079").orElseThrow();
		filings.upsertDocuments(target, List.of(new FilingIndexDocument(1, "10-K", "doc.htm", "10-K", 10L),
				new FilingIndexDocument(2, "EX-21", "ex21.htm", "EX-21", 5L)), "doc.htm");
		filings.recordStoredDocument(target.id(), "doc.htm",
				new StoredBlob("edgar/0000320193/000032019325000079/doc.htm", "a".repeat(64), 10), "text/html", NOW);
		filings.markFetched(target.id(), NOW);

		var detail = filings.findDetail("0000320193-25-000079").orElseThrow();
		assertThat(detail.status()).isEqualTo(Status.FETCHED);
		assertThat(detail.company().name()).isEqualTo("Apple Inc.");
		assertThat(detail.documents()).hasSize(2).first().satisfies(doc -> {
			assertThat(doc.primary()).isTrue();
			assertThat(doc.stored()).isTrue();
			assertThat(doc.sourceUrl())
				.isEqualTo("https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/doc.htm");
		});
		assertThat(detail.documents().get(1).stored()).isFalse();
		assertThat(filings.markFetching("0000320193-25-000079", NOW)).as("already fetched").isFalse();

		filings.markFailed("0000320193-25-000079", "boom", NOW);
		assertThat(filings.markFetching("0000320193-25-000079", NOW)).as("failed can be retried").isTrue();
	}

	@Test
	void tracksSyncState() {
		assertThat(filings.syncState(companyId).recentSyncedAt()).isNull();

		filings.markRecentSynced(companyId, NOW);
		filings.markHistoryImported(companyId, "CIK0000320193-submissions-001.json");
		filings.markHistoryImported(companyId, "CIK0000320193-submissions-001.json");

		var state = filings.syncState(companyId);
		assertThat(state.recentSyncedAt()).isEqualTo(NOW);
		assertThat(state.importedHistoryFiles()).containsExactly("CIK0000320193-submissions-001.json");
	}

	static FilingRecord record(String accession, String form, String filingDate) {
		return new FilingRecord(accession, form, LocalDate.parse(filingDate), null, null, "doc.htm", form, null, null,
				true, true, null, null);
	}

}
