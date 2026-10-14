package com.filinglens.comparison.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

/** Word-level differences and similarity between two texts. */
final class WordDiff {

	/** One run of the diff. {@code op} is {@code equal}, {@code delete} or {@code insert}. */
	record Op(String op, String text) {
	}

	private static final Pattern TOKEN = Pattern.compile("\\w+|\\s+|[^\\w\\s]");

	private static final Pattern WORD = Pattern.compile("\\w+");

	private static final Pattern NUMBER = Pattern.compile("\\d[\\d,.]*");

	private WordDiff() {
	}

	/** Diff of {@code from} → {@code to}; concatenating equal+delete ops gives {@code from}, equal+insert gives {@code to}. */
	static List<Op> diff(String from, String to) {
		List<String> a = tokens(from);
		List<String> b = tokens(to);
		Patch<String> patch = DiffUtils.diff(a, b);
		List<Op> ops = new ArrayList<>();
		int position = 0;
		for (AbstractDelta<String> delta : patch.getDeltas()) {
			int start = delta.getSource().getPosition();
			append(ops, "equal", a.subList(position, start));
			append(ops, "delete", delta.getSource().getLines());
			append(ops, "insert", delta.getTarget().getLines());
			position = start + delta.getSource().size();
		}
		append(ops, "equal", a.subList(position, a.size()));
		return ops;
	}

	/** Dice coefficient over lower-cased word multisets, in [0, 1]. */
	static double similarity(String x, String y) {
		Map<String, Integer> a = bag(x);
		Map<String, Integer> b = bag(y);
		int sizeA = a.values().stream().mapToInt(Integer::intValue).sum();
		int sizeB = b.values().stream().mapToInt(Integer::intValue).sum();
		if (sizeA + sizeB == 0) {
			return 1;
		}
		int shared = 0;
		for (Map.Entry<String, Integer> entry : a.entrySet()) {
			shared += Math.min(entry.getValue(), b.getOrDefault(entry.getKey(), 0));
		}
		return 2.0 * shared / (sizeA + sizeB);
	}

	static int wordCount(String text) {
		Matcher matcher = WORD.matcher(text);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	/** True when the texts are identical except for numbers (years, dates, amounts). */
	static boolean differsOnlyInNumbers(String x, String y) {
		return !x.equals(y) && NUMBER.matcher(x).replaceAll("#").equals(NUMBER.matcher(y).replaceAll("#"));
	}

	private static List<String> tokens(String text) {
		List<String> tokens = new ArrayList<>();
		Matcher matcher = TOKEN.matcher(text);
		while (matcher.find()) {
			tokens.add(matcher.group());
		}
		return tokens;
	}

	private static Map<String, Integer> bag(String text) {
		Map<String, Integer> bag = new HashMap<>();
		Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
		while (matcher.find()) {
			bag.merge(matcher.group(), 1, Integer::sum);
		}
		return bag;
	}

	private static void append(List<Op> ops, String op, List<String> tokens) {
		if (tokens.isEmpty()) {
			return;
		}
		String text = String.join("", tokens);
		if (!ops.isEmpty() && ops.getLast().op().equals(op)) {
			ops.set(ops.size() - 1, new Op(op, ops.getLast().text() + text));
		}
		else {
			ops.add(new Op(op, text));
		}
	}

}
