package com.filinglens.document.internal;

import java.util.regex.Pattern;

/** Whitespace normalization shared by all parsers. Characters are otherwise kept verbatim for citations. */
final class TextNormalizer {

	private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD]");

	private static final Pattern SPACES = Pattern.compile("[\\s\\u00A0\\u2007\\u202F\\u2002\\u2003\\u2009]+");

	private static final Pattern PAGE_NUMBER = Pattern.compile("(?i)(page\\s*)?\\d{1,3}|[ivxlc]{1,7}|-\\s*\\d{1,3}\\s*-");

	private static final Pattern RUNNING_HEADER = Pattern.compile("(?i).*form\\s+10-[kq].*\\|\\s*\\d{1,3}");

	private TextNormalizer() {
	}

	static String normalize(String text) {
		if (text == null) {
			return "";
		}
		return SPACES.matcher(INVISIBLE.matcher(text).replaceAll("")).replaceAll(" ").strip();
	}

	/**
	 * Page furniture that is not part of the disclosure: page numbers, "Table of
	 * Contents" back-links and running headers such as "Apple Inc. | 2025 Form 10-K | 21".
	 */
	static boolean isPageArtifact(String normalized) {
		if (normalized.isEmpty()) {
			return true;
		}
		if (normalized.length() <= 12 && PAGE_NUMBER.matcher(normalized).matches()) {
			return true;
		}
		if (normalized.equalsIgnoreCase("table of contents") || normalized.equalsIgnoreCase("index")) {
			return true;
		}
		return normalized.length() <= 150 && RUNNING_HEADER.matcher(normalized).matches();
	}

}
