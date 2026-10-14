package com.filinglens.comparison;

import java.util.Optional;

/** Starts comparisons on behalf of other modules. */
public interface ComparisonRequests {

	/**
	 * Compares the filing with its suggested predecessor (the previous annual report,
	 * or the same quarter a year earlier), preparing both documents as needed.
	 * {@link ComparisonCompleted} is published when the result is ready.
	 * @return the comparison id, or empty if the filing has no earlier filing to
	 * compare with
	 */
	Optional<Long> compareWithPrevious(long filingId);

}
