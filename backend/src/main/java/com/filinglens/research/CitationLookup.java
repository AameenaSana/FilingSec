package com.filinglens.research;

import java.util.Optional;
import java.util.UUID;

/** Citation lookups for other modules. */
public interface CitationLookup {

	/** @return empty unless the citation belongs to one of the user's conversations */
	Optional<CitationSnapshot> find(UUID userId, long citationId);

}
