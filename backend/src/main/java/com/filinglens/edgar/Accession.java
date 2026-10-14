package com.filinglens.edgar;

import java.util.regex.Pattern;

/**
 * SEC accession numbers, the unique identifier of a filing, in the canonical
 * dashed form {@code 0000320193-25-000079} (filer-agent CIK, year, sequence).
 */
public final class Accession {

	private static final Pattern DASHED = Pattern.compile("\\d{10}-\\d{2}-\\d{6}");

	private static final Pattern PLAIN = Pattern.compile("\\d{18}");

	private Accession() {
	}

	public static boolean isValid(String value) {
		return value != null && (DASHED.matcher(value).matches() || PLAIN.matcher(value).matches());
	}

	/** Accepts the dashed or 18-digit form and returns the dashed form. */
	public static String normalize(String value) {
		if (value != null && DASHED.matcher(value).matches()) {
			return value;
		}
		if (value != null && PLAIN.matcher(value).matches()) {
			return value.substring(0, 10) + "-" + value.substring(10, 12) + "-" + value.substring(12);
		}
		throw new IllegalArgumentException("Invalid accession number: " + value);
	}

	/** The 18-digit form used in EDGAR archive paths. */
	public static String withoutDashes(String accession) {
		return normalize(accession).replace("-", "");
	}

}
