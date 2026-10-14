package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.research.internal.RagEvalDataset.Question;
import com.filinglens.research.internal.Retriever.Retrieved;

/**
 * Measures retrieval quality on the evaluation set: for each question, where the
 * passage that answers it ranks among retrieved chunks. Runs keyword-only unless
 * {@code EMBEDDING_API_KEY} is set, in which case hybrid retrieval is measured.
 * The report is written to {@code build/reports/rag-eval/retrieval.md}; the
 * thresholds guard against regressions in chunking, indexing and ranking.
 */
@IntegrationTest
class RagRetrievalEvaluationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final double MIN_RECALL_AT_5 = 0.75;

	static final double MIN_MRR = 0.55;

	@Autowired
	IndexRepository index;

	@Autowired
	Retriever retriever;

	@Autowired
	EmbeddingModel embeddings;

	@Autowired
	JdbcClient jdbc;

	@Test
	void retrievesTheAnsweringPassage() {
		RagEvalDataset dataset = RagEvalDataset.load();
		long filingId = indexCorpus(jdbc, index, embeddings, dataset);

		List<Integer> ranks = new ArrayList<>();
		StringBuilder rows = new StringBuilder();
		for (Question q : dataset.questions()) {
			if (!q.answerable()) {
				continue;
			}
			List<Retrieved> retrieved = retriever.retrieve(List.of(filingId), q.question(), 12);
			int rank = dataset.rankOfExpected(q, retrieved.stream().map(Retrieved::chunk).toList());
			ranks.add(rank);
			rows.append("| %s | %s | %s |%n".formatted(q.id(), q.question(), rank == 0 ? "missed" : rank));
		}

		double recall1 = recallAt(ranks, 1);
		double recall3 = recallAt(ranks, 3);
		double recall5 = recallAt(ranks, 5);
		double mrr = ranks.stream().mapToDouble(r -> r == 0 ? 0 : 1.0 / r).average().orElse(0);
		String mode = embeddings.available() ? "hybrid (" + embeddings.modelName() + " + full text)" : "keyword only";
		String report = """
				# Retrieval evaluation

				Mode: %s. Questions: %d. Chunks indexed: %d.

				| Metric | Value |
				|---|---|
				| Recall@1 | %.2f |
				| Recall@3 | %.2f |
				| Recall@5 | %.2f |
				| MRR | %.3f |

				| Question | Text | Rank of answering passage |
				|---|---|---|
				%s""".formatted(mode, ranks.size(), chunkCount(jdbc, filingId), recall1, recall3, recall5, mrr, rows);
		RagEvalDataset.writeReport("retrieval.md", report);
		System.out.println(report);

		assertThat(recall5).as("recall@5").isGreaterThanOrEqualTo(MIN_RECALL_AT_5);
		assertThat(mrr).as("mean reciprocal rank").isGreaterThanOrEqualTo(MIN_MRR);
	}

	static double recallAt(List<Integer> ranks, int k) {
		return ranks.stream().filter(r -> r > 0 && r <= k).count() / (double) ranks.size();
	}

	/** Stores the corpus as a filing and indexes it, with embeddings when available. */
	static long indexCorpus(JdbcClient jdbc, IndexRepository index, EmbeddingModel embeddings,
			RagEvalDataset dataset) {
		jdbc.sql("DELETE FROM company").update();
		long companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0009999999', :name, now(), now())
				RETURNING id
				""").param("name", dataset.company()).query(Long.class).single();
		long filingId = jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, report_date, created_at, updated_at)
				VALUES (:c, '0009999999-26-000001', '10-K', DATE '2026-02-20', DATE '2025-12-31', now(), now())
				RETURNING id
				""").param("c", companyId).query(Long.class).single();
		var chunks = Chunker.chunk(dataset.sections());
		List<float[]> vectors = embeddings.available()
				? embeddings.embedDocuments(chunks.stream().map(Chunker.Chunk::text).toList()) : null;
		index.startIndex(filingId, 1, NOW);
		index.replaceChunks(filingId, chunks, vectors, embeddings.available() ? embeddings.modelName() : null, NOW);
		return filingId;
	}

	private static int chunkCount(JdbcClient jdbc, long filingId) {
		return jdbc.sql("SELECT count(*) FROM text_chunk WHERE filing_id = :f")
			.param("f", filingId)
			.query(Integer.class)
			.single();
	}

}
