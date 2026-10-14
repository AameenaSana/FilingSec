package com.filinglens.identity.internal;

import java.util.Locale;

import com.filinglens.platform.error.InvalidRequestException;

/**
 * Password rules following NIST SP 800-63B: a minimum length and no composition
 * rules. Length bounds are also enforced by request validation; this class
 * covers rules that need context.
 */
final class PasswordPolicy {

	static final int MIN_LENGTH = 12;

	static final int MAX_LENGTH = 128;

	private PasswordPolicy() {
	}

	static void check(String password, String normalizedEmail) {
		if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
			throw weak("Password must be between %d and %d characters".formatted(MIN_LENGTH, MAX_LENGTH));
		}
		String lower = password.toLowerCase(Locale.ROOT);
		String localPart = normalizedEmail.contains("@") ? normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
				: normalizedEmail;
		if (lower.equals(normalizedEmail) || (localPart.length() >= 4 && lower.contains(localPart))) {
			throw weak("Password must not contain your email address");
		}
		if (password.chars().distinct().count() < 4) {
			throw weak("Password is too repetitive");
		}
	}

	private static InvalidRequestException weak(String message) {
		return new InvalidRequestException("weak-password", message);
	}

}
