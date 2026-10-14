package com.filinglens.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filinglens.identity.UserPrincipal;

/**
 * Verifies credentials. Returns an outcome instead of throwing so that failed
 * attempt counters are committed. Unknown emails and locked accounts still run a
 * password hash comparison, keeping response timing uniform.
 */
@Service
class LoginService {

	sealed interface Outcome {

		record Success(UserPrincipal principal) implements Outcome {
		}

		record InvalidCredentials() implements Outcome {
		}

		record EmailNotVerified() implements Outcome {
		}

		record Disabled() implements Outcome {
		}

	}

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final IdentityProperties properties;

	private final Clock clock;

	private final String timingEqualizationHash;

	LoginService(UserRepository users, PasswordEncoder passwordEncoder, IdentityProperties properties, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.properties = properties;
		this.clock = clock;
		this.timingEqualizationHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	@Transactional
	Outcome authenticate(String email, String password) {
		Instant now = clock.instant();
		Optional<User> found = users.findByEmail(EmailAddresses.normalize(email));
		if (found.isEmpty() || found.get().isLocked(now)) {
			passwordEncoder.matches(password, timingEqualizationHash);
			return new Outcome.InvalidCredentials();
		}
		User user = found.get();
		if (!passwordEncoder.matches(password, user.passwordHash())) {
			user.recordFailedLogin(properties.maxFailedLogins(), properties.lockoutDuration(), now);
			return new Outcome.InvalidCredentials();
		}
		switch (user.status()) {
			case DISABLED -> {
				return new Outcome.Disabled();
			}
			case PENDING_VERIFICATION -> {
				return new Outcome.EmailNotVerified();
			}
			case ACTIVE -> {
			}
		}
		if (passwordEncoder.upgradeEncoding(user.passwordHash())) {
			user.upgradePasswordHash(passwordEncoder.encode(password), now);
		}
		user.recordSuccessfulLogin(now);
		return new Outcome.Success(user.toPrincipal());
	}

}
