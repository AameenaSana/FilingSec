package com.filinglens.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.filinglens.platform.error.InvalidRequestException;

class AccountServiceTest {

	static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

	final UserRepository users = mock(UserRepository.class);

	final TokenService tokens = mock(TokenService.class);

	final PasswordEncoder encoder = mock(PasswordEncoder.class);

	final IdentityMailer mailer = mock(IdentityMailer.class);

	final UserSessions sessions = mock(UserSessions.class);

	final IdentityProperties properties = new IdentityProperties(5, Duration.ofMinutes(15), Duration.ofHours(24),
			Duration.ofHours(1));

	AccountService service;

	@BeforeEach
	void setUp() {
		service = new AccountService(users, tokens, encoder, mailer, sessions, properties,
				Clock.fixed(NOW, ZoneOffset.UTC));
		when(encoder.encode(anyString())).thenAnswer(inv -> "{argon2}" + inv.getArgument(0));
		when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void registerCreatesPendingUserAndSendsVerification() {
		when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
		when(tokens.issue(any(), any(), any())).thenReturn("raw-token");

		service.register(" New@Example.com ", "a good long passphrase", " Jane Analyst ");

		verify(users).save(org.mockito.ArgumentMatchers.argThat(u -> u.email().equals("new@example.com")
				&& u.status() == User.Status.PENDING_VERIFICATION && u.displayName().equals("Jane Analyst")
				&& u.passwordHash().equals("{argon2}a good long passphrase")));
		verify(mailer).sendVerification("new@example.com", "Jane Analyst", "raw-token");
	}

	@Test
	void registerWithExistingEmailRevealsNothingAndNotifiesOwner() {
		User existing = User.register("taken@example.com", "{argon2}x", "Owner", NOW);
		when(users.findByEmail("taken@example.com")).thenReturn(Optional.of(existing));

		service.register("taken@example.com", "a good long passphrase", "Someone");

		verify(users, never()).save(any());
		verify(encoder).encode("a good long passphrase");
		verify(mailer).sendAlreadyRegistered("taken@example.com");
		verify(mailer, never()).sendVerification(anyString(), anyString(), anyString());
	}

	@Test
	void verifyEmailActivatesAccount() {
		User user = User.register("new@example.com", "{argon2}x", "New", NOW);
		when(tokens.redeem("tok", UserToken.Type.EMAIL_VERIFICATION)).thenReturn(Optional.of(user.id()));
		when(users.findById(user.id())).thenReturn(Optional.of(user));

		service.verifyEmail("tok");

		assertThat(user.status()).isEqualTo(User.Status.ACTIVE);
		assertThat(user.emailVerified()).isTrue();
	}

	@Test
	void invalidTokenIsRejected() {
		when(tokens.redeem("bad", UserToken.Type.EMAIL_VERIFICATION)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.verifyEmail("bad")).isInstanceOf(InvalidRequestException.class)
			.extracting("code")
			.isEqualTo("invalid-token");
	}

	@Test
	void forgotPasswordForUnknownEmailDoesNothingObservable() {
		when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

		service.requestPasswordReset("nobody@example.com");

		verifyNoInteractions(tokens, mailer);
	}

	@Test
	void resetPasswordChangesHashAndRevokesAllSessions() {
		User user = User.register("a@example.com", "{argon2}old", "A", NOW);
		when(tokens.redeem("tok", UserToken.Type.PASSWORD_RESET)).thenReturn(Optional.of(user.id()));
		when(users.findById(user.id())).thenReturn(Optional.of(user));

		service.resetPassword("tok", "a brand new passphrase");

		assertThat(user.passwordHash()).isEqualTo("{argon2}a brand new passphrase");
		assertThat(user.emailVerified()).isTrue();
		verify(sessions).revokeAll(user.id(), null);
		verify(mailer).sendPasswordChanged("a@example.com");
	}

	@Test
	void changePasswordRequiresCurrentPasswordAndKeepsCallerSession() {
		User user = User.register("a@example.com", "{argon2}old", "A", NOW);
		when(users.findById(user.id())).thenReturn(Optional.of(user));
		when(encoder.matches("wrong", "{argon2}old")).thenReturn(false);
		when(encoder.matches("current passphrase", "{argon2}old")).thenReturn(true);

		assertThatThrownBy(() -> service.changePassword(user.id(), "wrong", "a brand new passphrase", "s1"))
			.isInstanceOf(InvalidRequestException.class)
			.extracting("code")
			.isEqualTo("invalid-password");

		service.changePassword(user.id(), "current passphrase", "a brand new passphrase", "s1");

		assertThat(user.passwordHash()).isEqualTo("{argon2}a brand new passphrase");
		verify(sessions).revokeAll(user.id(), "s1");
	}

	@Test
	void deleteRequiresPassword() {
		User user = User.register("a@example.com", "{argon2}pw", "A", NOW);
		when(users.findById(user.id())).thenReturn(Optional.of(user));
		when(encoder.matches("wrong", "{argon2}pw")).thenReturn(false);

		assertThatThrownBy(() -> service.delete(user.id(), "wrong", "s1")).isInstanceOf(InvalidRequestException.class);
		verify(users, never()).delete(any());
	}

}
