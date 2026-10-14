package com.filinglens.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class LoginServiceTest {

	static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

	final UserRepository users = mock(UserRepository.class);

	final PasswordEncoder encoder = mock(PasswordEncoder.class);

	final IdentityProperties properties = new IdentityProperties(3, Duration.ofMinutes(15), Duration.ofHours(24),
			Duration.ofHours(1));

	LoginService service;

	User user;

	@BeforeEach
	void setUp() {
		when(encoder.encode(anyString())).thenReturn("{argon2}dummy");
		service = new LoginService(users, encoder, properties, Clock.fixed(NOW, ZoneOffset.UTC));
		user = User.register("analyst@example.com", "{argon2}hash", "Analyst", NOW.minusSeconds(3600));
		user.markEmailVerified(NOW.minusSeconds(3600));
		when(users.findByEmail("analyst@example.com")).thenReturn(Optional.of(user));
	}

	@Test
	void succeedsWithCorrectPasswordAndNormalizesEmail() {
		when(encoder.matches("secret-password", "{argon2}hash")).thenReturn(true);

		var outcome = service.authenticate("  Analyst@Example.COM ", "secret-password");

		assertThat(outcome).isInstanceOfSatisfying(LoginService.Outcome.Success.class,
				s -> assertThat(s.principal().email()).isEqualTo("analyst@example.com"));
	}

	@Test
	void unknownEmailStillComparesAHashForUniformTiming() {
		var outcome = service.authenticate("nobody@example.com", "whatever-password");

		assertThat(outcome).isInstanceOf(LoginService.Outcome.InvalidCredentials.class);
		verify(encoder).matches("whatever-password", "{argon2}dummy");
	}

	@Test
	void repeatedFailuresLockTheAccount() {
		when(encoder.matches("wrong", "{argon2}hash")).thenReturn(false);
		when(encoder.matches("secret-password", "{argon2}hash")).thenReturn(true);

		for (int i = 0; i < 3; i++) {
			assertThat(service.authenticate("analyst@example.com", "wrong"))
				.isInstanceOf(LoginService.Outcome.InvalidCredentials.class);
		}
		assertThat(user.isLocked(NOW)).isTrue();

		// Even the correct password is rejected while locked, without checking the real hash.
		assertThat(service.authenticate("analyst@example.com", "secret-password"))
			.isInstanceOf(LoginService.Outcome.InvalidCredentials.class);
		verify(encoder, times(0)).matches("secret-password", "{argon2}hash");
	}

	@Test
	void unverifiedEmailIsReportedOnlyAfterCorrectPassword() {
		User pending = User.register("new@example.com", "{argon2}hash2", "New", NOW);
		when(users.findByEmail("new@example.com")).thenReturn(Optional.of(pending));
		when(encoder.matches("secret-password", "{argon2}hash2")).thenReturn(true);

		assertThat(service.authenticate("new@example.com", "wrong-password"))
			.isInstanceOf(LoginService.Outcome.InvalidCredentials.class);
		assertThat(service.authenticate("new@example.com", "secret-password"))
			.isInstanceOf(LoginService.Outcome.EmailNotVerified.class);
	}

	@Test
	void upgradesOutdatedHashOnSuccessfulLogin() {
		when(encoder.matches("secret-password", "{argon2}hash")).thenReturn(true);
		when(encoder.upgradeEncoding("{argon2}hash")).thenReturn(true);
		when(encoder.encode("secret-password")).thenReturn("{argon2}upgraded");

		service.authenticate("analyst@example.com", "secret-password");

		assertThat(user.passwordHash()).isEqualTo("{argon2}upgraded");
	}

}
