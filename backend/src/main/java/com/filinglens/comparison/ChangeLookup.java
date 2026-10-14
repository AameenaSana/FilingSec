package com.filinglens.comparison;

import java.util.Optional;

/** Change lookups for other modules. Comparisons hold public filing data and are shared. */
public interface ChangeLookup {

	Optional<ChangeSnapshot> find(long comparisonId, String sectionKey, int changeOrdinal);

}
