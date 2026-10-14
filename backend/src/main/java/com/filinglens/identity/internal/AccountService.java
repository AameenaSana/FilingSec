package com.filinglens.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filinglens.platform.error.InvalidRequestException;
import com.filinglens.platform.error.UnauthorizedException;

/**
 * Account lifecycle. Operations that take an email address (register, resend
 * verification, forgot password) behave identically whether or not the address
 * is registered, so they cannot be used to discover accounts.
 */
@Service
@Transactional
class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	private final UserRepository users;

	private final TokenService tokens;

	private final PasswordEncoder passwordEncoder;

	private final IdentityMailer mailer;

	private final UserSessions sessions;

	private final IdentityProperties properties;

	private final Clock clock;

	AccountService(UserRepository users, TokenService tokens, PasswordEncoder passwordEncoder, IdentityMailer mailer,
			UserSessions sessions, IdentityProperties properties, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.mailer = mailer;
		this.sessions = sessions;
		this.properties = properties;
		this.clock = clock;
	}

	void register(String email, String password, String displayName) {
		String normalized = EmailAddresses.normalize(email);
		PasswordPolicy.check(password, normalized);
		var existing = users.findByEmail(normalized);
		if (existing.isPresent()) {
			// Hash anyway so both paths take the same time.
			passwordEncoder.encode(password);
			mailer.sendAlreadyRegistered(normalized);
			return;
		}
		User user = users.save(
				User.register(normalized, passwordEncoder.encode(password), displayName.strip(), clock.instant()));
		String token = tokens.issue(user.id(), UserToken.Type.EMAIL_VERIFICATION, properties.emailVerificationTtl());
		mailer.sendVerification(user.email(), user.displayName(), token);
		log.info("Registered user {}", user.id());
	}

	void resendVerification(String email) {
		users.findByEmail(EmailAddresses.normalize(email))
			.filter(user -> user.status() == User.Status.PENDING_VERIFICATION)
			.ifPresent(user -> {
				String token = tokens.issue(user.id(), UserToken.Type.EMAIL_VERIFICATION,
						properties.emailVerificationTtl());
				mailer.sendVerification(user.email(), user.displayName(), token);
			});
	}

	void verifyEmail(String token) {
		UUID userId = tokens.redeem(token, UserToken.Type.EMAIL_VERIFICATION).orElseThrow(AccountService::invalidLink);
		User user = users.findById(userId).orElseThrow(AccountService::invalidLink);
		user.markEmailVerified(clock.instant());
		log.info("Verified email for user {}", userId);
	}

	void requestPasswordReset(String email) {
		users.findByEmail(EmailAddresses.normalize(email))
			.filter(user -> user.status() != User.Status.DISABLED)
			.ifPresent(user -> {
				String token = tokens.issue(user.id(), UserToken.Type.PASSWORD_RESET, properties.passwordResetTtl());
				mailer.sendPasswordReset(user.email(), token);
			});
	}

	/** Sets a new password from a reset link and signs out every session of the user. */
	void resetPassword(String token, String newPassword) {
		UUID userId = tokens.redeem(token, UserToken.Type.PASSWORD_RESET).orElseThrow(AccountService::invalidLink);
		User user = users.findById(userId).orElseThrow(AccountService::invalidLink);
		PasswordPolicy.check(newPassword, user.email());
		Instant now = clock.instant();
		user.changePassword(passwordEncoder.encode(newPassword), now);
		// Following the emailed link proves control of the address.
		user.markEmailVerified(now);
		sessions.revokeAll(userId, null);
		mailer.sendPasswordChanged(user.email());
		log.info("Reset password for user {}", userId);
	}

	@Transactional(readOnly = true)
	AccountView get(UUID userId) {
		return AccountView.of(load(userId));
	}

	AccountView rename(UUID userId, String displayName) {
		User user = load(userId);
		user.rename(displayName.strip(), clock.instant());
		return AccountView.of(user);
	}

	/** Changes the password and signs out all other sessions, keeping the caller's. */
	void changePassword(UUID userId, String currentPassword, String newPassword, String currentSessionId) {
		User user = load(userId);
		requirePassword(user, currentPassword);
		PasswordPolicy.check(newPassword, user.email());
		user.changePassword(passwordEncoder.encode(newPassword), clock.instant());
		sessions.revokeAll(userId, currentSessionId);
		mailer.sendPasswordChanged(user.email());
		log.info("Changed password for user {}", userId);
	}

	/** Permanently deletes the account; owned data is removed by database cascades. */
	void delete(UUID userId, String password, String currentSessionId) {
		User user = load(userId);
		requirePassword(user, password);
		users.delete(user);
		sessions.revokeAll(userId, currentSessionId);
		log.info("Deleted user {}", userId);
	}

	private User load(UUID userId) {
		return users.findById(userId)
			.orElseThrow(() -> new UnauthorizedException("unauthorized", "Your session is no longer valid"));
	}

	private void requirePassword(User user, String password) {
		if (!passwordEncoder.matches(password, user.passwordHash())) {
			throw new InvalidRequestException("invalid-password", "The current password is incorrect");
		}
	}

	private static InvalidRequestException invalidLink() {
		return new InvalidRequestException("invalid-token", "This link is invalid, expired or has already been used");
	}

}
