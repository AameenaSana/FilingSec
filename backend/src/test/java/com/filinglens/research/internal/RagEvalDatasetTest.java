package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.filinglens.research.internal.IndexRepository.ChunkRow;

/** Checks the evaluation set itself, without a database. */
class RagEvalDatasetTest {

	final RagEvalDataset dataset = RagEvalDataset.load();

	@Test
	void everyAnswerableQuestionPointsAtOneBlockInItsSection() {
		assertThat(dataset.questions()).hasSizeGreaterThanOrEqualTo(25);
		for (var q : dataset.questions()) {
			if (q.answerable()) {
				assertThat(dataset.expectedRef(q)).as(q.id()).startsWith(q.section() + "/p");
			}
		}
		assertThat(dataset.questions()).anyMatch(q -> !q.answerable());
	}

	@Test
	void corpusChunksIntoSeveralPassagesSoRankingIsMeaningful() {
		assertThat(Chunker.chunk(dataset.sections())).hasSizeGreaterThanOrEqualTo(10);
	}

	@Test
	void ranksTheFirstChunkContainingTheExpectedBlock() {
		var q = dataset.questions().getFirst();
		String ref = dataset.expectedRef(q);
		ChunkRow other = new ChunkRow(1, 1, "X", "X", List.of(1), List.of("X/p1"), List.of("x"));
		ChunkRow hit = new ChunkRow(2, 1, "X", "X", List.of(2), List.of(ref), List.of("y"));

		assertThat(dataset.rankOfExpected(q, List.of(other, hit))).isEqualTo(2);
		assertThat(dataset.rankOfExpected(q, List.of(other))).isZero();
	}

}
