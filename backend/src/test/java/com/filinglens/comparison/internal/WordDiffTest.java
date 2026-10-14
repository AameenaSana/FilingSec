package com.filinglens.comparison.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WordDiffTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "The cat sat on the mat.|The dog sat on the red mat!",
			"Net sales were $391.0 billion.|Net sales were $416.2 billion, up 6%.", "|Entirely new text.",
			"Removed entirely.|" })
	void opsReconstructBothTexts(String from, String to) {
		String a = from == null ? "" : from;
		String b = to == null ? "" : to;

		List<WordDiff.Op> ops = WordDiff.diff(a, b);

		assertThat(join(ops, "insert")).as("equal + delete").isEqualTo(a);
		assertThat(join(ops, "delete")).as("equal + insert").isEqualTo(b);
		for (int i = 1; i < ops.size(); i++) {
			assertThat(ops.get(i).op()).as("adjacent runs are merged").isNotEqualTo(ops.get(i - 1).op());
		}
	}

	@Test
	void similarityIsAWordOverlapScore() {
		assertThat(WordDiff.similarity("risk factors", "Risk Factors")).isEqualTo(1.0);
		assertThat(WordDiff.similarity("alpha beta", "gamma delta")).isZero();
		assertThat(WordDiff.similarity("a b c d", "a b x y")).isEqualTo(0.5);
	}

	@Test
	void numericOnlyDifferences() {
		assertThat(WordDiff.differsOnlyInNumbers("fiscal 2024 sales of $1,234.5", "fiscal 2025 sales of $2,000"))
			.isTrue();
		assertThat(WordDiff.differsOnlyInNumbers("fiscal 2024 sales", "fiscal 2025 revenue")).isFalse();
		assertThat(WordDiff.differsOnlyInNumbers("same", "same")).isFalse();
	}

	private static String join(List<WordDiff.Op> ops, String excluded) {
		return ops.stream().filter(op -> !op.op().equals(excluded)).map(WordDiff.Op::text).collect(Collectors.joining());
	}

}
