package com.filinglens.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Issues and redeems single-use email tokens. Raw tokens carry 256 bits of
 * entropy and are never stored; lookups use their SHA-256 hash.
 */
@Component
class TokenService {

	private final SecureRandom random = new SecureRandom();

	private final UserTokenRepository tokens;

	private final Clock clock;

	TokenService(UserTokenRepository tokens, Clock clock) {
		this.tokens = tokens;
		this.clock = clock;
	}

	/** Issues a new token, revoking any unused token of the same type. Returns the raw token. */
	String issue(UUID userId, UserToken.Type type, Duration ttl) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = clock.instant();
		tokens.deleteUnused(userId, type);
		tokens.save(new UserToken(userId, type, hash(raw), now.plus(ttl), now));
		return raw;
	}

	/** Marks the token used and returns its user, if the token is valid, unused and unexpired. */
	Optional<UUID> redeem(String rawToken, UserToken.Type type) {
		if (rawToken == null || rawToken.isBlank() || rawToken.length() > 100) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		return tokens.findByTokenHashAndType(hash(rawToken), type).filter(t -> t.isUsable(now)).map(t -> {
			t.markUsed(now);
			return t.userId();
		});
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

}
