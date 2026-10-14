package com.filinglens.comparison.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.filinglens.IntegrationTest;
import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.ai.AiUsage;
import com.filinglens.ai.QuotaExceededException;
import com.filinglens.comparison.internal.InterpretationService.InterpretationCitation;
import com.filinglens.comparison.internal.InterpretationService.InterpretationRepository;

@IntegrationTest
@TestPropertySource(properties = "filinglens.ai.daily-token-limit-per-user=1000")
class InterpretationIntegrationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Autowired
	ComparisonRepository comparisons;

	@Autowired
	InterpretationRepository interpretations;

	@Autowired
	AiUsage usage;

	@Autowired
	JdbcClient jdbc;

	long sectionId;

	UUID user;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM app_user").update();
		jdbc.sql("DELETE FROM llm_usage").update();
		long companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0000320193', 'Apple Inc.', now(), now())
				RETURNING id
				""").query(Long.class).single();
		long base = filing(companyId, "0000320193-24-000123");
		long target = filing(companyId, "0000320193-25-000079");
		long comparisonId = comparisons.findOrCreate(base, target, 1, NOW);
		comparisons.saveResult(comparisonId,
				ComparisonEngine.compare(List.of(ComparisonEngineTest.section("10K.ITEM_1A", ComparisonEngineTest.AI_2024)),
						List.of(ComparisonEngineTest.section("10K.ITEM_1A", ComparisonEngineTest.AI_2025))),
				NOW);
		sectionId = comparisons.sectionId(comparisonId, "10K.ITEM_1A").orElseThrow();
		user = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, email, password_hash, display_name, status, password_changed_at, created_at, updated_at)
				VALUES (:id, 'u@example.com', 'x', 'U', 'ACTIVE', now(), now(), now())
				""").param("id", user).update();
	}

	@Test
	void interpretationLifecycle() {
		long id = interpretations.startPending(sectionId, 1, user);
		assertThat(interpretations.find(sectionId, 1).orElseThrow().status()).isEqualTo("PENDING");
		assertThat(interpretations.requester(id)).contains(user);

		interpretations.finish(id, "READY", "HIGH", "Reliance on AI increased.",
				List.of(new InterpretationCitation(1, 1, "generative", 0, 10, true)), 1, 1, "claude-opus-5-5", 100, 20,
				null);
		var view = interpretations.find(sectionId, 1).orElseThrow();
		assertThat(view.significance()).isEqualTo("HIGH");
		assertThat(view.citations()).singleElement().extracting(InterpretationCitation::changeOrdinal).isEqualTo(1);

		interpretations.finish(id, "FAILED", null, null, List.of(), null, null, null, 0, 0, "boom");
		assertThat(interpretations.startPending(sectionId, 1, user)).as("failed is reset in place").isEqualTo(id);
	}

	@Test
	void dailyQuotaIsEnforcedPerUser() {
		usage.checkQuota(user);
		usage.record(user, "answer", new AiResponse(List.of(), false, "end_turn", "claude-opus-5-5", 900, 100, 0));

		assertThatThrownBy(() -> usage.checkQuota(user)).isInstanceOf(QuotaExceededException.class);
	}

	private long filing(long companyId, String accession) {
		return jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, created_at, updated_at)
				VALUES (:c, :a, '10-K', DATE '2025-10-31', now(), now()) RETURNING id
				""").param("c", companyId).param("a", accession).query(Long.class).single();
	}

}
