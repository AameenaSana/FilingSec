package com.filinglens.filing;

import java.util.Optional;

/** Filing lookups for other modules. */
public interface FilingCatalog {

	/** @param accessionNumber dashed or undashed form */
	Optional<FilingRef> findByAccession(String accessionNumber);

	Optional<FilingRef> findById(long filingId);

}
