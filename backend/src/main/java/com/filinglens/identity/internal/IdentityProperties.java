package com.filinglens.identity.internal;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "filinglens.identity")
record IdentityProperties(@Min(1) int maxFailedLogins, @NotNull Duration lockoutDuration,
		@NotNull Duration emailVerificationTtl, @NotNull Duration passwordResetTtl) {
}
