package com.filinglens.comparison.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.comparison.internal.ComparisonViews.ChangeView;
import com.filinglens.comparison.internal.ComparisonViews.Status;
import com.filinglens.document.SectionText;

@IntegrationTest
class ComparisonIntegrationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Autowired
	ComparisonRepository comparisons;

	@Autowired
	JdbcClient jdbc;

	long base;

	long target;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		long companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0000320193', 'Apple Inc.', now(), now())
				RETURNING id
				""").query(Long.class).single();
		base = filing(companyId, "0000320193-24-000123");
		target = filing(companyId, "0000320193-25-000079");
	}

	@Test
	void comparisonLifecycleAndStoredResults() {
		long id = comparisons.findOrCreate(base, target, 1, NOW);
		assertThat(comparisons.findOrCreate(base, target, 1, NOW)).as("idempotent").isEqualTo(id);
		assertThat(comparisons.waitingFor(target)).containsExactly(id);
		assertThat(comparisons.transition(id, Status.WAITING, Status.COMPUTING, null, NOW)).isTrue();
		assertThat(comparisons.transition(id, Status.WAITING, Status.COMPUTING, null, NOW)).isFalse();

		List<SectionText> before = List.of(ComparisonEngineTest.section("10K.ITEM_1A", ComparisonEngineTest.INTRO,
				ComparisonEngineTest.AI_2024, ComparisonEngineTest.FISCAL_2024));
		List<SectionText> after = List.of(ComparisonEngineTest.section("10K.ITEM_1A", ComparisonEngineTest.INTRO,
				ComparisonEngineTest.AI_2025, ComparisonEngineTest.FISCAL_2025, ComparisonEngineTest.TARIFFS));
		comparisons.saveResult(id, ComparisonEngine.compare(before, after), NOW);

		var row = comparisons.find(id).orElseThrow();
		assertThat(row.status()).isEqualTo(Status.READY);
		assertThat(row.stats().modified()).isEqualTo(2);
		assertThat(row.stats().added()).isEqualTo(1);
		assertThat(row.stats().numericOnly()).isEqualTo(1);
		assertThat(comparisons.sections(id)).singleElement().satisfies(s -> assertThat(s.unchanged()).isEqualTo(1));

		List<ChangeView> all = comparisons.changes(id, "10K.ITEM_1A", true).orElseThrow();
		assertThat(all).extracting(ChangeView::type).containsExactly("MODIFIED", "MODIFIED", "ADDED");
		assertThat(all.getFirst().diff()).isNotEmpty();
		assertThat(all.getFirst().base().ref()).isEqualTo("10K.ITEM_1A/p2");
		assertThat(comparisons.changes(id, "10K.ITEM_1A", false).orElseThrow()).hasSize(2);
		assertThat(comparisons.changes(id, "10K.ITEM_9", true)).isEmpty();
		assertThat(comparisons.waitingFor(target)).isEmpty();
	}

	private long filing(long companyId, String accession) {
		return jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, created_at, updated_at)
				VALUES (:c, :a, '10-K', DATE '2025-10-31', now(), now()) RETURNING id
				""").param("c", companyId).param("a", accession).query(Long.class).single();
	}

}
