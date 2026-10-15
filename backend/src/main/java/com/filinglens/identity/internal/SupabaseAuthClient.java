package com.filinglens.identity.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves a Supabase access token to its user with {@code GET /auth/v1/user}.
 * Asking Supabase (instead of verifying the JWT locally) works with both legacy and
 * asymmetric signing keys and rejects tokens of sessions that have been signed out.
 */
@Component
class SupabaseAuthClient {

	/**
	 * @param emailConfirmed whether Supabase has confirmed the address
	 * @param provider the provider of the sign-in, e.g. {@code google}
	 * @param fullName the name reported by the provider, or an empty string
	 */
	record SupabaseUser(UUID id, String email, boolean emailConfirmed, String provider, String fullName) {
	}

	private final SupabaseProperties properties;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	SupabaseAuthClient(SupabaseProperties properties, JsonMapper json) {
		this.properties = properties;
		this.json = json;
	}

	/** Returns the token's user, or empty when Supabase does not accept the token. */
	Optional<SupabaseUser> fetchUser(String accessToken) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url() + "/auth/v1/user"))
			.timeout(Duration.ofSeconds(10))
			.header("apikey", properties.publishableKey())
			.header("Authorization", "Bearer " + accessToken)
			.header("Accept", "application/json")
			.GET()
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new IllegalStateException("Supabase user request failed: " + ex.getMessage(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while contacting Supabase", ex);
		}
		int status = response.statusCode();
		if (status == 401 || status == 403) {
			return Optional.empty();
		}
		if (status != 200) {
			throw new IllegalStateException("Supabase user request failed with status " + status);
		}
		return Optional.of(parse(response.body()));
	}

	SupabaseUser parse(String body) {
		JsonNode user = json.readTree(body);
		String confirmedAt = user.path("email_confirmed_at").asString("");
		JsonNode metadata = user.path("user_metadata");
		String fullName = metadata.path("full_name").asString("");
		if (fullName.isBlank()) {
			fullName = metadata.path("name").asString("");
		}
		return new SupabaseUser(UUID.fromString(user.path("id").asString("")), user.path("email").asString(""),
				!confirmedAt.isBlank(), user.path("app_metadata").path("provider").asString(""), fullName.strip());
	}

}
