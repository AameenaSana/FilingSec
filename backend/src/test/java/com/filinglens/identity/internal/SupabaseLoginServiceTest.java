package com.filinglens.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import tools.jackson.databind.json.JsonMapper;

class SupabaseLoginServiceTest {

	static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

	static final UUID SUPABASE_ID = UUID.fromString("7f0c3f8e-2a51-4f4e-9d1b-0d8e7c1a2b3c");

	final SupabaseAuthClient supabase = mock(SupabaseAuthClient.class);

	final UserRepository users = mock(UserRepository.class);

	final PasswordEncoder encoder = mock(PasswordEncoder.class);

	final SupabaseProperties properties = new SupabaseProperties("https://demo.supabase.co/", "sb_publishable_x",
			List.of("google"));

	SupabaseLoginService service;

	@BeforeEach
	void setUp() {
		when(encoder.encode(anyString())).thenReturn("{argon2}random");
		when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(users.findBySupabaseUserId(any())).thenReturn(Optional.empty());
		when(users.findByEmail(anyString())).thenReturn(Optional.empty());
		service = new SupabaseLoginService(supabase, properties, users, encoder, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void newGoogleUserGetsAnActiveVerifiedAccount() {
		signsInAs(google("Analyst@Example.com", "Ada Analyst"));

		var outcome = service.authenticate("token");

		assertThat(outcome).isInstanceOfSatisfying(SupabaseLoginService.Outcome.Success.class,
				s -> assertThat(s.principal().email()).isEqualTo("analyst@example.com"));
		verify(users).save(any(User.class));
	}

	@Test
	void verifiedPasswordAccountIsLinkedAndKeepsItsPassword() {
		User existing = User.register("analyst@example.com", "{argon2}hash", "Analyst", NOW.minusSeconds(60));
		existing.markEmailVerified(NOW.minusSeconds(60));
		when(users.findByEmail("analyst@example.com")).thenReturn(Optional.of(existing));
		signsInAs(google("analyst@example.com", "Ada"));

		assertThat(service.authenticate("token")).isInstanceOf(SupabaseLoginService.Outcome.Success.class);

		assertThat(existing.supabaseUserId()).isEqualTo(SUPABASE_ID);
		assertThat(existing.passwordHash()).isEqualTo("{argon2}hash");
		assertThat(existing.displayName()).isEqualTo("Analyst");
		verify(users, never()).save(any(User.class));
	}

	@Test
	void unverifiedPasswordAccountLosesThePasswordWhenTheOwnerSignsIn() {
		User squatted = User.register("analyst@example.com", "{argon2}attacker", "Someone", NOW.minusSeconds(60));
		when(users.findByEmail("analyst@example.com")).thenReturn(Optional.of(squatted));
		signsInAs(google("analyst@example.com", "Ada"));

		assertThat(service.authenticate("token")).isInstanceOf(SupabaseLoginService.Outcome.Success.class);

		assertThat(squatted.passwordHash()).isEqualTo("{argon2}random");
		assertThat(squatted.status()).isEqualTo(User.Status.ACTIVE);
	}

	@Test
	void accountLinkedToAnotherSupabaseUserIsNotTakenOver() {
		User linked = User.registerExternal("analyst@example.com", "{argon2}x", "Ada", UUID.randomUUID(),
				NOW.minusSeconds(60));
		when(users.findByEmail("analyst@example.com")).thenReturn(Optional.of(linked));
		signsInAs(google("analyst@example.com", "Ada"));

		assertThat(service.authenticate("token")).isInstanceOf(SupabaseLoginService.Outcome.Conflict.class);
	}

	@Test
	void returningUserIsFoundByTheLinkedIdEvenAfterAnEmailChange() {
		User linked = User.registerExternal("old@example.com", "{argon2}x", "Ada", SUPABASE_ID, NOW.minusSeconds(60));
		when(users.findBySupabaseUserId(SUPABASE_ID)).thenReturn(Optional.of(linked));
		signsInAs(google("new@example.com", "Ada"));

		assertThat(service.authenticate("token")).isInstanceOfSatisfying(SupabaseLoginService.Outcome.Success.class,
				s -> assertThat(s.principal().id()).isEqualTo(linked.id()));
	}

	@Test
	void rejectsTokensSupabaseDoesNotAccept() {
		when(supabase.fetchUser("token")).thenReturn(Optional.empty());

		assertThat(service.authenticate("token")).isInstanceOf(SupabaseLoginService.Outcome.Rejected.class);
	}

	@Test
	void rejectsUnconfirmedEmailsAndProvidersThatAreNotAllowed() {
		signsInAs(new SupabaseAuthClient.SupabaseUser(SUPABASE_ID, "a@example.com", false, "google", ""));
		assertThat(service.authenticate("token")).isInstanceOf(SupabaseLoginService.Outcome.Rejected.class);

		signsInAs(new SupabaseAuthClient.SupabaseUser(SUPABASE_ID, "a@example.com", true, "github", ""));
		assertThat(service.authenticate("token")).isInstanceOf(SupabaseLoginService.Outcome.Rejected.class);
	}

	@Test
	void disabledAccountsCannotSignIn() {
		User linked = User.registerExternal("a@example.com", "{argon2}x", "Ada", SUPABASE_ID, NOW.minusSeconds(60));
		org.springframework.test.util.ReflectionTestUtils.setField(linked, "status", User.Status.DISABLED);
		when(users.findBySupabaseUserId(SUPABASE_ID)).thenReturn(Optional.of(linked));
		signsInAs(google("a@example.com", "Ada"));

		assertThat(service.authenticate("token")).isInstanceOf(SupabaseLoginService.Outcome.Disabled.class);
	}

	@Test
	void displayNameFallsBackToTheEmailAndIsCapped() {
		assertThat(SupabaseLoginService.displayName("  ", "ada@example.com")).isEqualTo("ada");
		assertThat(SupabaseLoginService.displayName("Ada\u0007 Lovelace", "ada@example.com")).isEqualTo("Ada Lovelace");
		assertThat(SupabaseLoginService.displayName("x".repeat(150), "ada@example.com")).hasSize(100);
	}

	@Test
	void parsesTheSupabaseUserResponse() {
		var client = new SupabaseAuthClient(properties, JsonMapper.builder().build());

		var user = client.parse("""
				{"id":"7f0c3f8e-2a51-4f4e-9d1b-0d8e7c1a2b3c","email":"ada@example.com",
				 "email_confirmed_at":"2026-03-01T11:00:00Z","app_metadata":{"provider":"google","providers":["google"]},
				 "user_metadata":{"full_name":"Ada Lovelace","email_verified":true}}
				""");

		assertThat(user).isEqualTo(new SupabaseAuthClient.SupabaseUser(SUPABASE_ID, "ada@example.com", true, "google",
				"Ada Lovelace"));
		assertThat(properties.url()).isEqualTo("https://demo.supabase.co");
	}

	private void signsInAs(SupabaseAuthClient.SupabaseUser user) {
		when(supabase.fetchUser("token")).thenReturn(Optional.of(user));
	}

	private static SupabaseAuthClient.SupabaseUser google(String email, String name) {
		return new SupabaseAuthClient.SupabaseUser(SUPABASE_ID, email, true, "google", name);
	}

}
