package com.filinglens.identity.internal;

import java.util.Locale;

final class EmailAddresses {

	private EmailAddresses() {
	}

	/** Canonical form used for storage and lookup: trimmed and lower-cased. */
	static String normalize(String email) {
		return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
	}

}
