package com.filinglens.research.internal;

import java.util.List;
import java.util.function.Consumer;

/**
 * Port for generating a grounded answer. The model receives passages whose
 * content is a list of source blocks and returns answer segments, each with the
 * citations (passage index and block range) that support it.
 */
interface AnswerModel {

	boolean available();

	/**
	 * Streams the answer.
	 * @param onText receives answer text as it is generated
	 */
	Answer answer(AnswerRequest request, Consumer<String> onText);

	record Turn(boolean fromUser, String text) {
	}

	/**
	 * @param title shown to the model, e.g. "Apple Inc. 10-K (period ended 2025-09-27) — Item 1A Risk Factors"
	 * @param source a stable identifier of the passage
	 * @param blocks the passage's source blocks, in order; citations refer to their indices
	 */
	record Passage(String title, String source, List<String> blocks) {
	}

	record AnswerRequest(String system, List<Turn> history, List<Passage> passages, String question) {
	}

	/**
	 * @param passageIndex index into {@link AnswerRequest#passages()}
	 * @param startBlock first cited block (inclusive)
	 * @param endBlock last cited block (exclusive)
	 */
	record Cite(int passageIndex, int startBlock, int endBlock, String citedText) {
	}

	record Segment(String text, List<Cite> citations) {
	}

	/**
	 * @param refused the model declined to answer (safety refusal)
	 */
	record Answer(List<Segment> segments, boolean refused, String stopReason, String model, long inputTokens,
			long outputTokens, long cacheReadTokens) {

		String text() {
			return String.join("", segments.stream().map(Segment::text).toList());
		}

	}

}
