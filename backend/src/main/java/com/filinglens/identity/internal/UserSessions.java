package com.filinglens.identity.internal;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Revokes a user's server-side sessions, e.g. after a password change. */
@Component
class UserSessions {

	private static final Logger log = LoggerFactory.getLogger(UserSessions.class);

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	UserSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.sessions = sessions;
	}

	/**
	 * @param keepSessionId a session to keep (the caller's own), or {@code null} to
	 * revoke all
	 */
	void revokeAll(UUID userId, String keepSessionId) {
		var userSessions = sessions.findByPrincipalName(userId.toString());
		userSessions.keySet()
			.stream()
			.filter(id -> !id.equals(keepSessionId))
			.forEach(sessions::deleteById);
		log.info("Revoked {} session(s) for user {}",
				userSessions.size() - (userSessions.containsKey(keepSessionId) ? 1 : 0), userId);
	}

}
