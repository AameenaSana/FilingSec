package com.filinglens.edgar;

import java.util.regex.Pattern;

/**
 * Canonical public sec.gov URLs for filings and their documents. These are the
 * source links shown to users and preserved for citations.
 */
public final class EdgarUrls {

	public static final String WWW = "https://www.sec.gov";

	/** Archive file names: letters, digits, dot, dash and underscore; no path separators. */
	private static final Pattern FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

	private EdgarUrls() {
	}

	public static boolean isValidFilename(String filename) {
		return filename != null && FILENAME.matcher(filename).matches() && !filename.contains("..");
	}

	/** Archive directory path, e.g. {@code /Archives/edgar/data/320193/000032019325000079}. */
	public static String archivePath(String cik, String accession) {
		return "/Archives/edgar/data/" + Long.parseLong(Cik.normalize(cik)) + "/" + Accession.withoutDashes(accession);
	}

	/** The human-readable filing index page listing all documents. */
	public static String filingIndexUrl(String cik, String accession) {
		return WWW + filingIndexPath(cik, accession);
	}

	public static String filingIndexPath(String cik, String accession) {
		return archivePath(cik, accession) + "/" + Accession.normalize(accession) + "-index.htm";
	}

	public static String documentUrl(String cik, String accession, String filename) {
		return WWW + documentPath(cik, accession, filename);
	}

	public static String documentPath(String cik, String accession, String filename) {
		if (!isValidFilename(filename)) {
			throw new IllegalArgumentException("Invalid archive file name: " + filename);
		}
		return archivePath(cik, accession) + "/" + filename;
	}

}
