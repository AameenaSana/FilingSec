package com.filinglens.filing.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The filing forms FilingLens tracks: annual, quarterly and current reports, and their amendments. */
final class FormTypes {

	static final List<String> BASE = List.of("10-K", "10-Q", "8-K");

	static final Set<String> TRACKED = Set.of("10-K", "10-K/A", "10-Q", "10-Q/A", "8-K", "8-K/A");

	private FormTypes() {
	}

	static boolean isTracked(String form) {
		return form != null && TRACKED.contains(form);
	}

	/** Expands base forms to include their amendments, e.g. 10-K → 10-K, 10-K/A. */
	static List<String> withAmendments(Collection<String> baseForms) {
		Set<String> forms = new LinkedHashSet<>();
		for (String form : baseForms == null || baseForms.isEmpty() ? BASE : baseForms) {
			if (BASE.contains(form)) {
				forms.add(form);
				forms.add(form + "/A");
			}
		}
		return List.copyOf(forms);
	}

}
