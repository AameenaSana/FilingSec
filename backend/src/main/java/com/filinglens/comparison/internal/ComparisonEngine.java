package com.filinglens.comparison.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.filinglens.document.BlockText;
import com.filinglens.document.SectionText;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

/**
 * Deterministic period-over-period text comparison of two parsed filings.
 * <ol>
 * <li>Sections are aligned by their stable keys.</li>
 * <li>Within a matched section, blocks are diffed in order by content hash; equal
 * runs are unchanged.</li>
 * <li>Remaining blocks are paired by word similarity (greedy, best first); pairs
 * are MODIFIED with a word-level diff, the rest ADDED or REMOVED.</li>
 * <li>An ADDED block with the exact text of a REMOVED block elsewhere becomes one
 * MOVED change.</li>
 * </ol>
 * Changes are ordered by their position in the later filing.
 */
final class ComparisonEngine {

	static final int ALGORITHM_VERSION = 1;

	static final double SIMILARITY_THRESHOLD = 0.5;

	static final double SHORT_BLOCK_THRESHOLD = 0.7;

	/** Above this many candidate pairs in one section, only exact matches are paired. */
	static final long MAX_PAIRINGS = 250_000;

	/** Shorter texts are too generic (e.g. headings) to be reported as moved. */
	static final int MIN_MOVED_LENGTH = 40;

	enum ChangeType {

		ADDED, REMOVED, MODIFIED, MOVED

	}

	enum Alignment {

		MATCHED, ADDED, REMOVED

	}

	record Change(ChangeType type, BlockText base, BlockText target, Double similarity, List<WordDiff.Op> wordDiff,
			boolean numericOnly, double position) {
	}

	record SectionResult(String key, String title, Alignment alignment, List<Change> changes, int unchanged) {

		long count(ChangeType type) {
			return changes.stream().filter(c -> c.type() == type).count();
		}

		long numericOnly() {
			return changes.stream().filter(Change::numericOnly).count();
		}

	}

	private ComparisonEngine() {
	}

	static List<SectionResult> compare(List<SectionText> base, List<SectionText> target) {
		Map<String, SectionText> baseByKey = new LinkedHashMap<>();
		base.forEach(s -> baseByKey.put(s.key(), s));
		Set<String> targetKeys = new HashSet<>();

		List<SectionResult> results = new ArrayList<>();
		for (SectionText section : target) {
			targetKeys.add(section.key());
			SectionText previous = baseByKey.get(section.key());
			if (previous == null) {
				List<Change> added = new ArrayList<>();
				for (int i = 0; i < section.blocks().size(); i++) {
					added.add(new Change(ChangeType.ADDED, null, section.blocks().get(i), null, null, false, i));
				}
				results.add(new SectionResult(section.key(), section.title(), Alignment.ADDED, added, 0));
			}
			else {
				results.add(compareSection(previous, section));
			}
		}
		for (SectionText section : base) {
			if (!targetKeys.contains(section.key())) {
				List<Change> removed = new ArrayList<>();
				for (int i = 0; i < section.blocks().size(); i++) {
					removed.add(new Change(ChangeType.REMOVED, section.blocks().get(i), null, null, null, false, i));
				}
				results.add(new SectionResult(section.key(), section.title(), Alignment.REMOVED, removed, 0));
			}
		}
		return detectMoves(results);
	}

	private static SectionResult compareSection(SectionText base, SectionText target) {
		List<BlockText> a = base.blocks();
		List<BlockText> b = target.blocks();
		Patch<String> patch = DiffUtils.diff(a.stream().map(BlockText::contentHash).toList(),
				b.stream().map(BlockText::contentHash).toList());

		List<Integer> removed = new ArrayList<>();
		List<Integer> added = new ArrayList<>();
		// Where each base block sits in the target, for ordering removed blocks.
		double[] basePosition = new double[a.size()];
		int baseIndex = 0;
		int targetIndex = 0;
		int unchanged = 0;
		for (AbstractDelta<String> delta : patch.getDeltas()) {
			while (baseIndex < delta.getSource().getPosition()) {
				basePosition[baseIndex++] = targetIndex++;
				unchanged++;
			}
			// Removed blocks interleave with the delta's inserted blocks by offset.
			int deltaStart = targetIndex;
			for (int i = 0; i < delta.getSource().size(); i++) {
				removed.add(baseIndex);
				basePosition[baseIndex++] = deltaStart + i - 0.25;
			}
			for (int i = 0; i < delta.getTarget().size(); i++) {
				added.add(targetIndex++);
			}
		}
		while (baseIndex < a.size()) {
			basePosition[baseIndex++] = targetIndex++;
			unchanged++;
		}

		List<Change> changes = new ArrayList<>();
		Set<Integer> pairedBase = new HashSet<>();
		Set<Integer> pairedTarget = new HashSet<>();
		if ((long) removed.size() * added.size() <= MAX_PAIRINGS) {
			record Pair(int base, int target, double similarity) {
			}
			List<Pair> pairs = new ArrayList<>();
			for (int i : removed) {
				for (int j : added) {
					double similarity = WordDiff.similarity(a.get(i).text(), b.get(j).text());
					if (similarity >= threshold(a.get(i).text(), b.get(j).text())) {
						pairs.add(new Pair(i, j, similarity));
					}
				}
			}
			pairs.sort(Comparator.comparingDouble(Pair::similarity).reversed()
				.thenComparingInt(Pair::target)
				.thenComparingInt(Pair::base));
			for (Pair pair : pairs) {
				if (!pairedBase.contains(pair.base()) && !pairedTarget.contains(pair.target())) {
					pairedBase.add(pair.base());
					pairedTarget.add(pair.target());
					String from = a.get(pair.base()).text();
					String to = b.get(pair.target()).text();
					changes.add(new Change(ChangeType.MODIFIED, a.get(pair.base()), b.get(pair.target()),
							round(pair.similarity()), WordDiff.diff(from, to), WordDiff.differsOnlyInNumbers(from, to),
							pair.target()));
				}
			}
		}
		for (int i : removed) {
			if (!pairedBase.contains(i)) {
				changes.add(new Change(ChangeType.REMOVED, a.get(i), null, null, null, false, basePosition[i]));
			}
		}
		for (int j : added) {
			if (!pairedTarget.contains(j)) {
				changes.add(new Change(ChangeType.ADDED, null, b.get(j), null, null, false, j));
			}
		}
		changes.sort(Comparator.comparingDouble(Change::position));
		return new SectionResult(target.key(), target.title(), Alignment.MATCHED, changes, unchanged);
	}

	/** Merges an ADDED and a REMOVED block with identical text into one MOVED change. */
	private static List<SectionResult> detectMoves(List<SectionResult> sections) {
		Map<String, List<Change>> removedByHash = new HashMap<>();
		for (SectionResult section : sections) {
			for (Change change : section.changes()) {
				if (change.type() == ChangeType.REMOVED && change.base().text().length() >= MIN_MOVED_LENGTH) {
					removedByHash.computeIfAbsent(change.base().contentHash(), h -> new ArrayList<>()).add(change);
				}
			}
		}
		if (removedByHash.isEmpty()) {
			return sections;
		}
		Set<Change> consumed = new HashSet<>();
		Map<Change, Change> moves = new HashMap<>();
		for (SectionResult section : sections) {
			for (Change change : section.changes()) {
				if (change.type() != ChangeType.ADDED) {
					continue;
				}
				List<Change> candidates = removedByHash.get(change.target().contentHash());
				if (candidates != null && !candidates.isEmpty()) {
					Change removed = candidates.removeFirst();
					consumed.add(removed);
					moves.put(change, new Change(ChangeType.MOVED, removed.base(), change.target(), 1.0, null, false,
							change.position()));
				}
			}
		}
		List<SectionResult> result = new ArrayList<>();
		for (SectionResult section : sections) {
			List<Change> changes = section.changes()
				.stream()
				.filter(c -> !consumed.contains(c))
				.map(c -> moves.getOrDefault(c, c))
				.toList();
			result.add(new SectionResult(section.key(), section.title(), section.alignment(), changes, section.unchanged()));
		}
		return result;
	}

	private static double threshold(String x, String y) {
		return Math.min(WordDiff.wordCount(x), WordDiff.wordCount(y)) < 6 ? SHORT_BLOCK_THRESHOLD : SIMILARITY_THRESHOLD;
	}

	private static double round(double value) {
		return Math.round(value * 1000) / 1000.0;
	}

}
