package com.filinglens.document;

import java.util.List;

/** Read access to parsed filing text for other modules. */
public interface DocumentContent {

	enum State {

		/** Not parsed yet (possibly not downloaded yet). */
		PENDING,
		/** Sections and blocks are available. */
		PARSED,
		/** Parsing cannot succeed: unsupported format or a permanent failure. */
		UNAVAILABLE

	}

	State state(long filingId);

	/**
	 * Makes sure the filing will be parsed: requests its download if needed, or a
	 * parse if it is downloaded but not parsed with the current parser.
	 */
	void prepare(long filingId);

	/** Sections in document order; empty unless {@link State#PARSED}. */
	List<SectionText> read(long filingId);

}
