package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.research.internal.AnswerModel.Answer;
import com.filinglens.research.internal.AnswerModel.Cite;
import com.filinglens.research.internal.AnswerModel.Segment;

@IntegrationTest
class ResearchIntegrationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Autowired
	IndexRepository index;

	@Autowired
	ConversationRepository conversations;

	@Autowired
	JdbcClient jdbc;

	long filingId;

	UUID alice;

	UUID bob;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM app_user").update();
		long companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0000320193', 'Apple Inc.', now(), now())
				RETURNING id
				""").query(Long.class).single();
		filingId = jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, report_date, created_at, updated_at)
				VALUES (:c, '0000320193-25-000079', '10-K', DATE '2025-10-31', DATE '2025-09-27', now(), now()) RETURNING id
				""").param("c", companyId).query(Long.class).single();
		alice = user("alice@example.com");
		bob = user("bob@example.com");
	}

	@Test
	void hybridSearchFindsChunksByKeywordsAndVectors() {
		var chunks = Chunker.chunk(List.of(ChunkerAndCitationTest.section("10K.ITEM_1A",
				"HEADING:Item 1A. Risk Factors", "Generative artificial intelligence features may not be successful.",
				"HEADING:Supply Chain", "The Company depends on outsourcing partners for manufacturing.")));
		assertThat(chunks).hasSize(1);
		float[] vector = new float[1024];
		vector[0] = 1;
		assertThat(index.startIndex(filingId, 1, NOW)).isTrue();
		index.replaceChunks(filingId, chunks, List.of(vector), "voyage-4", NOW);

		assertThat(index.state(filingId).orElseThrow().embedded()).isTrue();
		assertThat(index.textSearch(List.of(filingId), "What does Apple say about artificial intelligence?", 5)).hasSize(1);
		assertThat(index.textSearch(List.of(filingId), "zebra", 5)).isEmpty();
		assertThat(index.vectorSearch(List.of(filingId), vector, 5)).hasSize(1);
		assertThat(index.vectorSearch(List.of(-1L), vector, 5)).isEmpty();
		var loaded = index.load(index.textSearch(List.of(filingId), "outsourcing", 5)).getFirst();
		assertThat(loaded.blockRefs()).hasSize(4);
		assertThat(loaded.blockTexts().get(3)).contains("outsourcing partners");
		assertThat(index.startIndex(filingId, 1, NOW)).as("already indexed").isFalse();
	}

	@Test
	void conversationsAreIsolatedAndStoreCitations() {
		var chunks = Chunker.chunk(List.of(ChunkerAndCitationTest.section("10K.ITEM_1A", "AI features may not be successful.")));
		index.startIndex(filingId, 1, NOW);
		index.replaceChunks(filingId, chunks, null, "voyage-4", NOW);
		var chunk = index.load(index.textSearch(List.of(filingId), "AI", 1)).getFirst();

		long id = conversations.create(alice, "Apple 10-K", List.of(filingId), NOW);
		assertThat(conversations.find(bob, id)).isEmpty();
		assertThat(conversations.list(bob, 10)).isEmpty();
		assertThat(conversations.delete(bob, id)).isFalse();

		long question = conversations.addMessage(id, "USER", "What AI risks?", "COMPLETE", NOW);
		long answer = conversations.addMessage(id, "ASSISTANT", "", "STREAMING", NOW);
		var mapped = CitationMapper.map(new Answer(List.of(new Segment("AI may fail.",
				List.of(new Cite(0, 0, 1, "AI features may not be successful.")))), false, "end_turn", "m", 1, 1, 0),
				List.of(chunk));
		conversations.finishMessage(answer, "AI may fail.", "COMPLETE", "claude-opus-5-5", 10L, 5L, 0L, null);
		conversations.saveCitations(answer, mapped);

		var messages = conversations.messages(id);
		assertThat(messages).extracting(m -> m.role()).containsExactly("USER", "ASSISTANT");
		var citation = messages.get(1).citations().getFirst();
		assertThat(citation.filing().companyName()).isEqualTo("Apple Inc.");
		assertThat(citation.blockRefs()).containsExactly("10K.ITEM_1A/p1");
		assertThat(citation.verified()).isTrue();
		assertThat(conversations.history(id, answer + 1, 6)).extracting(AnswerModel.Turn::fromUser)
			.containsExactly(true, false);
		assertThat(conversations.history(id, question, 6)).isEmpty();

		conversations.recordUsage(alice, "answer", "claude-opus-5-5", 100, 50, NOW);
		assertThat(conversations.tokensUsedSince(alice, NOW.minusSeconds(60))).isEqualTo(150);
		assertThat(conversations.tokensUsedSince(bob, NOW.minusSeconds(60))).isZero();
	}

	private UUID user(String email) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, email, password_hash, display_name, status, password_changed_at, created_at, updated_at)
				VALUES (:id, :email, 'x', 'Test', 'ACTIVE', now(), now(), now())
				""").param("id", id).param("email", email).update();
		return id;
	}

}
