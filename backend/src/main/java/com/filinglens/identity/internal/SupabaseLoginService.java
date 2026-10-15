package com.filinglens.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filinglens.identity.UserPrincipal;

/**
 * Signs in a user authenticated by Supabase (e.g. with Google). The Supabase user
 * is matched to an account by its linked Supabase id, otherwise by its confirmed
 * email address; new addresses get a new, already verified account.
 */
@Service
class SupabaseLoginService {

	private static final Logger log = LoggerFactory.getLogger(SupabaseLoginService.class);

	private static final int MAX_DISPLAY_NAME = 100;

	sealed interface Outcome {

		record Success(UserPrincipal principal) implements Outcome {
		}

		/** The token is invalid, or the identity is not one FilingLens accepts. */
		record Rejected() implements Outcome {
		}

		/** The email belongs to an account already linked to another Supabase user. */
		record Conflict() implements Outcome {
		}

		record Disabled() implements Outcome {
		}

	}

	private final SupabaseAuthClient supabase;

	private final SupabaseProperties properties;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	SupabaseLoginService(SupabaseAuthClient supabase, SupabaseProperties properties, UserRepository users,
			PasswordEncoder passwordEncoder, Clock clock) {
		this.supabase = supabase;
		this.properties = properties;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	boolean enabled() {
		return properties.configured();
	}

	@Transactional
	Outcome authenticate(String accessToken) {
		Optional<SupabaseAuthClient.SupabaseUser> found = supabase.fetchUser(accessToken);
		if (found.isEmpty()) {
			return new Outcome.Rejected();
		}
		SupabaseAuthClient.SupabaseUser identity = found.get();
		String email = EmailAddresses.normalize(identity.email());
		if (!identity.emailConfirmed() || email.isEmpty() || !properties.providers().contains(identity.provider())) {
			return new Outcome.Rejected();
		}
		Instant now = clock.instant();
		User user = users.findBySupabaseUserId(identity.id()).orElse(null);
		if (user == null) {
			user = users.findByEmail(email).orElse(null);
			if (user == null) {
				user = users.save(User.registerExternal(email, unusablePasswordHash(),
						displayName(identity.fullName(), email), identity.id(), now));
				log.info("Registered user {} through Supabase ({})", user.id(), identity.provider());
			}
			else if (user.supabaseUserId() != null) {
				return new Outcome.Conflict();
			}
			else {
				if (!user.emailVerified()) {
					// Whoever registered this address never proved control of it, so their
					// password must not keep working once the real owner takes the account.
					user.changePassword(unusablePasswordHash(), now);
				}
				user.linkSupabase(identity.id(), now);
				user.markEmailVerified(now);
				log.info("Linked user {} to Supabase ({})", user.id(), identity.provider());
			}
		}
		if (user.status() == User.Status.DISABLED) {
			return new Outcome.Disabled();
		}
		user.recordSuccessfulLogin(now);
		return new Outcome.Success(user.toPrincipal());
	}

	private String unusablePasswordHash() {
		return passwordEncoder.encode(UUID.randomUUID() + "-" + UUID.randomUUID());
	}

	static String displayName(String fullName, String email) {
		String name = fullName == null ? "" : fullName.replaceAll("\\p{Cntrl}", "").strip();
		if (name.isEmpty()) {
			name = email.substring(0, Math.max(email.indexOf('@'), 0));
		}
		if (name.isEmpty()) {
			name = "FilingLens user";
		}
		return name.length() > MAX_DISPLAY_NAME ? name.substring(0, MAX_DISPLAY_NAME).strip() : name;
	}

}
