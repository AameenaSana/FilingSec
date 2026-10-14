package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.ai.AiModel;
import com.filinglens.ai.AiModel.AiRequest;
import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.research.internal.CitationMapper.MappedCitation;
import com.filinglens.research.internal.IndexRepository.ChunkRow;
import com.filinglens.research.internal.RagEvalDataset.Question;
import com.filinglens.research.internal.ResearchViews.FilingInfo;
import com.filinglens.research.internal.Retriever.Retrieved;

/**
 * End-to-end answer evaluation against the live model: retrieval, the production
 * system prompt, grounded generation and citation verification. It calls the
 * Claude API (and the embedding API, if configured), so it costs money and only
 * runs when {@code RAG_EVAL_LIVE=true} and {@code LLM_API_KEY} are set:
 *
 * <pre>RAG_EVAL_LIVE=true LLM_API_KEY=... ./gradlew test --tests '*RagAnswerEvaluationTest'</pre>
 *
 * The report is written to {@code build/reports/rag-eval/answers.md}.
 */
@IntegrationTest
@EnabledIfEnvironmentVariable(named = "RAG_EVAL_LIVE", matches = "true")
class RagAnswerEvaluationTest {

	/** Share of citations whose quoted text must be found verbatim in the cited blocks. */
	static final double MIN_VERIFIED = 0.95;

	/** Share of answerable questions whose answer must cite the passage that answers them. */
	static final double MIN_EXPECTED_CITED = 0.8;

	@Autowired
	IndexRepository index;

	@Autowired
	Retriever retriever;

	@Autowired
	EmbeddingModel embeddings;

	@Autowired
	AiModel model;

	@Autowired
	ResearchProperties properties;

	@Autowired
	JdbcClient jdbc;

	@Test
	void answersAreGroundedInTheRightPassages() {
		RagEvalDataset dataset = RagEvalDataset.load();
		long filingId = RagRetrievalEvaluationTest.indexCorpus(jdbc, index, embeddings, dataset);
		FilingInfo filing = new FilingInfo("0009999999-26-000001", "0009999999", dataset.company(), "10-K",
				LocalDate.of(2025, 12, 31), LocalDate.of(2026, 2, 20));

		int answerable = 0;
		int expectedCited = 0;
		int citations = 0;
		int verified = 0;
		int refusals = 0;
		long inputTokens = 0;
		long outputTokens = 0;
		StringBuilder rows = new StringBuilder();
		for (Question q : dataset.questions()) {
			List<ChunkRow> chunks = retriever.retrieve(List.of(filingId), q.question(), properties.passagesPerAnswer())
				.stream()
				.map(Retrieved::chunk)
				.toList();
			AiResponse answer = model.generate(new AiRequest(ResearchService.SYSTEM_PROMPT, List.of(),
					chunks.stream().map(c -> ResearchService.passage(c, filing)).toList(), q.question()), text -> {
					});
			inputTokens += answer.inputTokens();
			outputTokens += answer.outputTokens();
			if (answer.refused()) {
				refusals++;
			}
			List<MappedCitation> mapped = answer.refused() ? List.of() : CitationMapper.map(answer, chunks);
			citations += mapped.size();
			verified += (int) mapped.stream().filter(MappedCitation::verified).count();
			String outcome;
			if (q.answerable()) {
				answerable++;
				String ref = dataset.expectedRef(q);
				boolean cited = mapped.stream().anyMatch(c -> c.verified() && c.blockRefs().contains(ref));
				expectedCited += cited ? 1 : 0;
				outcome = cited ? "cited answering passage" : "**did not cite " + ref + "**";
			}
			else {
				outcome = mapped.isEmpty() ? "no citations (declined)" : mapped.size() + " citations — check answer";
			}
			rows.append("| %s | %d | %s | %s |%n".formatted(q.id(), mapped.size(), outcome,
					answer.text().replace("\n", " ").replace("|", "\\|")));
		}

		double verifiedRate = citations == 0 ? 0 : verified / (double) citations;
		double expectedRate = expectedCited / (double) answerable;
		String report = """
				# Answer evaluation

				Model: %s. Retrieval: %s. Questions: %d (%d answerable).

				| Metric | Value |
				|---|---|
				| Answering passage cited | %.2f |
				| Citations verified verbatim | %.2f (%d of %d) |
				| Refusals | %d |
				| Tokens (input / output) | %d / %d |

				| Question | Citations | Outcome | Answer |
				|---|---|---|---|
				%s""".formatted(model.getClass().getSimpleName(),
				embeddings.available() ? "hybrid" : "keyword only", dataset.questions().size(), answerable, expectedRate,
				verifiedRate, verified, citations, refusals, inputTokens, outputTokens, rows);
		RagEvalDataset.writeReport("answers.md", report);
		System.out.println(report);

		assertThat(verifiedRate).as("verified citation rate").isGreaterThanOrEqualTo(MIN_VERIFIED);
		assertThat(expectedRate).as("answering passage cited").isGreaterThanOrEqualTo(MIN_EXPECTED_CITED);
	}

}
