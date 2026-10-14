package com.filinglens.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.document.internal.DocumentViews.BlockView;
import com.filinglens.document.internal.SectionDetector.Confidence;
import com.filinglens.document.internal.SectionDetector.DetectedSection;

@IntegrationTest
class DocumentIntegrationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Autowired
	DocumentRepository documents;

	@Autowired
	JdbcClient jdbc;

	long filingId;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		long companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0000320193', 'Apple Inc.', now(), now())
				RETURNING id
				""").query(Long.class).single();
		filingId = jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, created_at, updated_at)
				VALUES (:c, '0000320193-25-000079', '10-K', DATE '2025-10-31', now(), now()) RETURNING id
				""").param("c", companyId).query(Long.class).single();
	}

	@Test
	void parseLifecycleAllowsRetryOnlyAfterFailureOrNewVersion() {
		assertThat(documents.startParse(filingId, 1, NOW)).isTrue();
		assertThat(documents.startParse(filingId, 1, NOW)).as("already parsing").isFalse();
		documents.markFinished(filingId, "FAILED", "boom", NOW);
		assertThat(documents.startParse(filingId, 1, NOW)).as("retry after failure").isTrue();
		save();
		assertThat(documents.startParse(filingId, 1, NOW)).as("already parsed").isFalse();
		assertThat(documents.startParse(filingId, 2, NOW)).as("new parser version").isTrue();
	}

	@Test
	void storesSectionsAndBlocksWithStableRefsAndOffsets() {
		documents.startParse(filingId, 1, NOW);
		save();

		assertThat(documents.state(filingId).orElseThrow().status()).isEqualTo("PARSED");
		assertThat(documents.sections(filingId)).extracting(s -> s.key()).containsExactly("COVER", "10K.ITEM_1A");

		List<BlockView> risk = documents.blocks(filingId, "10K.ITEM_1A", 1, 10);
		assertThat(risk).extracting(BlockView::ref).containsExactly("10K.ITEM_1A/p1", "10K.ITEM_1A/p2");
		BlockView first = documents.blocks(filingId, null, 1, 1).getFirst();
		assertThat(first.charStart()).isZero();
		assertThat(first.charEnd()).isEqualTo("FORM 10-K".length());
		assertThat(risk.getFirst().charStart()).isEqualTo("FORM 10-K".length() + 2);
		assertThat(risk.getLast().contentHash()).isEqualTo(DocumentRepository.sha256("AI risks may affect results."));
		assertThat(documents.blockByRef(filingId, "10K.ITEM_1A/p2")).get()
			.extracting(BlockView::text)
			.isEqualTo("AI risks may affect results.");

		// Re-parsing replaces earlier results.
		save();
		assertThat(documents.blocks(filingId, null, 1, 100)).hasSize(3);
	}

	private void save() {
		List<ParsedBlock> blocks = List.of(new ParsedBlock(1, BlockType.HEADING, "FORM 10-K"),
				new ParsedBlock(2, BlockType.HEADING, "Item 1A. Risk Factors"),
				new ParsedBlock(3, BlockType.PARAGRAPH, "AI risks may affect results."));
		List<DetectedSection> sections = List.of(new DetectedSection("COVER", "Cover", null, 1, 1, Confidence.MEDIUM),
				new DetectedSection("10K.ITEM_1A", "Risk Factors", "Item 1A. Risk Factors", 2, 3, Confidence.HIGH));
		documents.saveParsed(filingId, 1, sections, blocks, "processed/x/annotated.html", NOW);
	}

}
