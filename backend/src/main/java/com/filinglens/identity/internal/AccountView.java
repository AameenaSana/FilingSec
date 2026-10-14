package com.filinglens.identity.internal;

import java.time.Instant;
import java.util.UUID;

/** The current user's account as returned by the API. */
record AccountView(UUID id, String email, String displayName, String role, boolean emailVerified,
		Instant createdAt) {

	static AccountView of(User user) {
		return new AccountView(user.id(), user.email(), user.displayName(), user.role().name(), user.emailVerified(),
				user.createdAt());
	}

}
