package com.filinglens.identity.internal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies for the identity endpoints. */
final class IdentityRequests {

	private static final String NO_CONTROL_CHARS = "^[^\\p{Cntrl}]*$";

	private IdentityRequests() {
	}

	record Register(@NotBlank @Email @Size(max = 320) String email,
			@NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String password,
			@NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARS) String displayName) {
	}

	record Login(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String password) {
	}

	record EmailOnly(@NotBlank @Email @Size(max = 320) String email) {
	}

	record Token(@NotBlank @Size(max = 100) String token) {
	}

	record ResetPassword(@NotBlank @Size(max = 100) String token,
			@NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String newPassword) {
	}

	record UpdateAccount(@NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARS) String displayName) {
	}

	record ChangePassword(@NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String currentPassword,
			@NotBlank @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH) String newPassword) {
	}

	record DeleteAccount(@NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String password) {
	}

}
