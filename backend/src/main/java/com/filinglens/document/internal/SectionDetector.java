package com.filinglens.document.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.filinglens.document.internal.SectionCatalog.ItemDefinition;

/**
 * Assigns every block to a form section by finding item headings.
 * <p>
 * Filings usually mention each item heading twice: in the table of contents and
 * at the start of the section. For each item the occurrence followed by the most
 * text before the next heading is taken as the real section start; table-of-
 * contents entries are followed by almost nothing. Picks that would put items out
 * of their standard order are discarded (longest increasing subsequence).
 * Blocks before the first section form a {@code COVER} section; if no headings
 * are found, the whole document is one {@code DOCUMENT} section.
 */
final class SectionDetector {

	enum Confidence {

		HIGH, MEDIUM, LOW

	}

	/**
	 * @param headingText the heading as written in the filing; {@code null} for
	 * synthetic sections
	 */
	record DetectedSection(String key, String title, String headingText, int firstOrdinal, int lastOrdinal,
			Confidence confidence) {
	}

	private static final Pattern ITEM = Pattern
		.compile("(?i)^items?\\s*(\\d{1,2}(?:\\.\\d{1,2})?[a-c]?)(?![\\da-z])\\s*[.:\\-\\u2013\\u2014]?\\s*(.*)$");

	private static final Pattern PART = Pattern.compile("(?i)^part\\s+(iv|i{1,3})\\b.*");

	private static final int MAX_HEADING_LENGTH = 250;

	private record Candidate(int index, ItemDefinition item, String text, BlockType type) {
	}

	private SectionDetector() {
	}

	static boolean looksLikeHeading(String text) {
		return text.length() <= MAX_HEADING_LENGTH && (ITEM.matcher(text).matches() || PART.matcher(text).matches());
	}

	static List<DetectedSection> detect(String formType, List<ParsedBlock> blocks) {
		if (blocks.isEmpty()) {
			return List.of();
		}
		List<Candidate> candidates = candidates(formType, blocks);
		List<Candidate> chosen = inStandardOrder(formType, bestPerItem(candidates, blocks));
		int lastOrdinal = blocks.getLast().ordinal();
		if (chosen.isEmpty()) {
			return List.of(new DetectedSection(SectionCatalog.DOCUMENT_KEY, "Full document", null, 1, lastOrdinal,
					Confidence.LOW));
		}
		List<DetectedSection> sections = new ArrayList<>();
		int firstSectionOrdinal = blocks.get(chosen.getFirst().index()).ordinal();
		if (firstSectionOrdinal > 1) {
			sections.add(new DetectedSection(SectionCatalog.COVER_KEY, "Cover page and front matter", null, 1,
					firstSectionOrdinal - 1, Confidence.MEDIUM));
		}
		for (int i = 0; i < chosen.size(); i++) {
			Candidate candidate = chosen.get(i);
			int start = blocks.get(candidate.index()).ordinal();
			int end = i + 1 < chosen.size() ? blocks.get(chosen.get(i + 1).index()).ordinal() - 1 : lastOrdinal;
			int span = textLength(blocks, candidate.index() + 1, i + 1 < chosen.size() ? chosen.get(i + 1).index()
					: blocks.size());
			sections.add(new DetectedSection(candidate.item().key(), candidate.item().title(), candidate.text(), start,
					end, confidence(candidate, span)));
		}
		return sections;
	}

	private static List<Candidate> candidates(String formType, List<ParsedBlock> blocks) {
		List<Candidate> candidates = new ArrayList<>();
		String part = null;
		for (int i = 0; i < blocks.size(); i++) {
			ParsedBlock block = blocks.get(i);
			if (block.type() == BlockType.TABLE || block.text().length() > MAX_HEADING_LENGTH) {
				continue;
			}
			Matcher partMatcher = PART.matcher(block.text());
			if (partMatcher.matches()) {
				part = partMatcher.group(1).toUpperCase();
				// "PART II - OTHER INFORMATION Item 1. Legal Proceedings" in one block.
				Matcher inline = Pattern.compile("(?i)\\bitems?\\s*\\d").matcher(block.text());
				if (!inline.find()) {
					continue;
				}
			}
			Matcher item = ITEM.matcher(stripPartPrefix(block.text()));
			if (!item.matches()) {
				continue;
			}
			String number = item.group(1).toUpperCase();
			Optional<ItemDefinition> definition = SectionCatalog.find(formType,
					SectionCatalog.usesParts(formType) ? part : null, number);
			int index = i;
			definition.ifPresent(d -> candidates.add(new Candidate(index, d, block.text(), block.type())));
		}
		return candidates;
	}

	private static String stripPartPrefix(String text) {
		return text.replaceFirst("(?i)^part\\s+(iv|i{1,3})\\b[\\s.:\\-\\u2013\\u2014]*(?:[a-z ,]+?(?=items?\\s*\\d))?", "")
			.strip();
	}

	/** For each item, the occurrence followed by the most text before the next candidate heading. */
	private static List<Candidate> bestPerItem(List<Candidate> candidates, List<ParsedBlock> blocks) {
		Map<String, Candidate> best = new HashMap<>();
		Map<String, Integer> bestSpan = new HashMap<>();
		for (int i = 0; i < candidates.size(); i++) {
			Candidate candidate = candidates.get(i);
			int next = i + 1 < candidates.size() ? candidates.get(i + 1).index() : blocks.size();
			int span = textLength(blocks, candidate.index() + 1, next);
			String key = candidate.item().key();
			if (!best.containsKey(key) || span >= bestSpan.get(key)) {
				best.put(key, candidate);
				bestSpan.put(key, span);
			}
		}
		return best.values().stream().sorted(Comparator.comparingInt(Candidate::index)).toList();
	}

	/** Keeps the largest set of picks whose document order matches the form's item order. */
	private static List<Candidate> inStandardOrder(String formType, List<Candidate> picks) {
		int n = picks.size();
		if (n == 0) {
			return picks;
		}
		int[] length = new int[n];
		int[] previous = new int[n];
		int bestEnd = 0;
		for (int i = 0; i < n; i++) {
			length[i] = 1;
			previous[i] = -1;
			int order = SectionCatalog.order(formType, picks.get(i).item().key());
			for (int j = 0; j < i; j++) {
				if (SectionCatalog.order(formType, picks.get(j).item().key()) < order && length[j] + 1 > length[i]) {
					length[i] = length[j] + 1;
					previous[i] = j;
				}
			}
			if (length[i] > length[bestEnd]) {
				bestEnd = i;
			}
		}
		List<Candidate> result = new ArrayList<>();
		for (int i = bestEnd; i >= 0; i = previous[i]) {
			result.addFirst(picks.get(i));
		}
		return result;
	}

	private static Confidence confidence(Candidate candidate, int span) {
		if (span >= 1_000 && candidate.type() == BlockType.HEADING) {
			return Confidence.HIGH;
		}
		return span >= 200 ? Confidence.MEDIUM : Confidence.LOW;
	}

	private static int textLength(List<ParsedBlock> blocks, int fromIndex, int toIndex) {
		int length = 0;
		for (int i = fromIndex; i < toIndex && i < blocks.size(); i++) {
			length += blocks.get(i).text().length();
		}
		return length;
	}

}
