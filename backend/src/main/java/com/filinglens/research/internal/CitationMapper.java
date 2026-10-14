package com.filinglens.research.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.ai.AiModel.Cite;
import com.filinglens.ai.AiModel.Segment;
import com.filinglens.research.internal.IndexRepository.ChunkRow;

/**
 * Turns the model's citations (passage index + block range) into citations of
 * exact source blocks, and verifies each cited text against the stored blocks.
 * Citations that point outside the given passages are dropped.
 */
final class CitationMapper {

	/**
	 * @param ordinal the marker number shown in the answer; the same source range
	 * gets the same number
	 * @param verified the cited text appears verbatim (ignoring whitespace) in the
	 * referenced blocks
	 */
	record MappedCitation(int ordinal, ChunkRow chunk, List<String> blockRefs, int firstBlockOrdinal, String citedText,
			int answerStart, int answerEnd, boolean verified) {
	}

	private CitationMapper() {
	}

	static List<MappedCitation> map(AiResponse answer, List<ChunkRow> passages) {
		List<MappedCitation> citations = new ArrayList<>();
		Map<String, Integer> markers = new LinkedHashMap<>();
		int offset = 0;
		for (Segment segment : answer.segments()) {
			int start = offset;
			int end = offset + segment.text().length();
			offset = end;
			for (Cite cite : segment.citations()) {
				if (cite.passageIndex() < 0 || cite.passageIndex() >= passages.size()) {
					continue;
				}
				ChunkRow chunk = passages.get(cite.passageIndex());
				int blockCount = chunk.blockRefs().size();
				int first = Math.clamp(cite.startBlock(), 0, blockCount - 1);
				int last = Math.clamp(Math.max(cite.endBlock(), first + 1), first + 1, blockCount);
				List<String> refs = chunk.blockRefs().subList(first, last);
				String sourceText = String.join(" ", chunk.blockTexts().subList(first, last));
				String key = chunk.filingId() + ":" + String.join(",", refs);
				int ordinal = markers.computeIfAbsent(key, k -> markers.size() + 1);
				citations.add(new MappedCitation(ordinal, chunk, List.copyOf(refs), chunk.blockOrdinals().get(first),
						cite.citedText(), start, end, verify(sourceText, cite.citedText())));
			}
		}
		return citations;
	}

	static boolean verify(String sourceText, String citedText) {
		String cited = normalize(citedText);
		return !cited.isEmpty() && normalize(sourceText).contains(cited);
	}

	static String normalize(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").strip();
	}

}
