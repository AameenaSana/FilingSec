package com.filinglens.identity.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.filinglens.platform.error.InvalidRequestException;

class PasswordPolicyTest {

	@ParameterizedTest
	@ValueSource(strings = { "correct horse battery", "Tr0ub4dor&3-xyzzy", "a long passphrase with spaces" })
	void acceptsReasonablePasswords(String password) {
		assertThatCode(() -> PasswordPolicy.check(password, "analyst@example.com")).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "short", "aaaaaaaaaaaaaaaa", "abababababababab", "my-analyst-password",
			"analyst@example.com" })
	void rejectsWeakPasswords(String password) {
		assertThatThrownBy(() -> PasswordPolicy.check(password, "analyst@example.com"))
			.isInstanceOf(InvalidRequestException.class)
			.extracting("code")
			.isEqualTo("weak-password");
	}

	@ParameterizedTest
	@ValueSource(ints = { 129, 500 })
	void rejectsOverlongPasswords(int length) {
		String password = "abcdefghij".repeat(length / 10 + 1).substring(0, length);
		assertThatThrownBy(() -> PasswordPolicy.check(password, "a@example.com"))
			.isInstanceOf(InvalidRequestException.class);
	}

}
