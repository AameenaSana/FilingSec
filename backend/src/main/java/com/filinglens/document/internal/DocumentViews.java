package com.filinglens.document.internal;

import java.time.Instant;
import java.util.List;

/** API representations of parsed documents. */
final class DocumentViews {

	private DocumentViews() {
	}

	/**
	 * NOT_AVAILABLE: the filing's document has not been downloaded yet.
	 */
	enum ParseStatus {

		NOT_AVAILABLE, PARSING, PARSED, UNSUPPORTED, FAILED

	}

	record SectionView(String key, String title, String headingText, int ordinal, int firstBlock, int lastBlock,
			String confidence) {
	}

	record DocumentStructure(ParseStatus status, String detail, Integer parserVersion, Instant parsedAt,
			Integer blockCount, List<SectionView> sections) {
	}

	/**
	 * @param ordinal position in the document; the viewer anchor {@code data-fl-block}
	 * @param ref stable citation reference, e.g. {@code 10K.ITEM_1A/p12}
	 * @param charStart offset into the normalized document text
	 */
	record BlockView(int ordinal, String ref, String sectionKey, String type, String text, String contentHash,
			int charStart, int charEnd) {
	}

}
