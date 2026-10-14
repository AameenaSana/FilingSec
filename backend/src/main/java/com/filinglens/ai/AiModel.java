package com.filinglens.ai;

import java.util.List;
import java.util.function.Consumer;

/**
 * Generates grounded text. The model receives passages whose content is a list of
 * source blocks and returns text segments, each with the citations (passage index
 * and block range) that support it.
 */
public interface AiModel {

	/** False when no LLM is configured on this server. */
	boolean available();

	/**
	 * Generates a response, streaming text as it is produced.
	 * @param onText receives text increments; may be a no-op
	 * @throws AiNotConfiguredException if no LLM is configured
	 */
	AiResponse generate(AiRequest request, Consumer<String> onText);

	record Turn(boolean fromUser, String text) {
	}

	/**
	 * @param title shown to the model, e.g. "Apple Inc. 10-K (period ended 2025-09-27) — Risk Factors"
	 * @param source a stable identifier of the passage
	 * @param blocks the passage's source blocks, in order; citations refer to their indices
	 */
	record Passage(String title, String source, List<String> blocks) {
	}

	/**
	 * @param history earlier turns, alternating and starting with the user
	 * @param prompt the user's instruction or question, sent after the passages
	 */
	record AiRequest(String system, List<Turn> history, List<Passage> passages, String prompt) {
	}

	/**
	 * @param passageIndex index into {@link AiRequest#passages()}
	 * @param startBlock first cited block (inclusive)
	 * @param endBlock last cited block (exclusive)
	 */
	record Cite(int passageIndex, int startBlock, int endBlock, String citedText) {
	}

	record Segment(String text, List<Cite> citations) {
	}

	/** @param refused the model declined to respond (safety refusal) */
	record AiResponse(List<Segment> segments, boolean refused, String stopReason, String model, long inputTokens,
			long outputTokens, long cacheReadTokens) {

		public String text() {
			return String.join("", segments.stream().map(Segment::text).toList());
		}

	}

}
