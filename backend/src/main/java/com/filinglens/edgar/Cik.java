package com.filinglens.edgar;

import java.util.regex.Pattern;

/**
 * SEC Central Index Key helpers. The canonical form used throughout FilingLens is
 * the 10-digit zero-padded string, e.g. {@code 0000320193}.
 */
public final class Cik {

	private static final Pattern DIGITS = Pattern.compile("\\d{1,10}");

	private Cik() {
	}

	public static boolean isValid(String value) {
		return value != null && DIGITS.matcher(value.strip()).matches() && Long.parseLong(value.strip()) > 0;
	}

	/** @throws IllegalArgumentException if the value is not 1-10 digits */
	public static String normalize(String value) {
		if (!isValid(value)) {
			throw new IllegalArgumentException("Invalid CIK: " + value);
		}
		return normalize(Long.parseLong(value.strip()));
	}

	public static String normalize(long value) {
		if (value <= 0 || value > 9_999_999_999L) {
			throw new IllegalArgumentException("Invalid CIK: " + value);
		}
		return String.format("%010d", value);
	}

}
