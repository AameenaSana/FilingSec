package com.filinglens.identity;

import java.util.Optional;
import java.util.UUID;

/** Contact details of users, for modules that send them email. */
public interface UserDirectory {

	/** @return empty if the user does not exist */
	Optional<UserContact> contact(UUID userId);

	/**
	 * @param reachable whether the account is active with a verified email address,
	 * so email may be sent to it
	 */
	record UserContact(UUID id, String email, String displayName, boolean reachable) {
	}

}
