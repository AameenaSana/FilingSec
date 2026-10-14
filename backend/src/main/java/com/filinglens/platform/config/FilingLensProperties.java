package com.filinglens.platform.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed application configuration. Validated at startup so a misconfigured
 * deployment fails fast instead of misbehaving at runtime.
 */
@Validated
@ConfigurationProperties(prefix = "filinglens")
public record FilingLensProperties(@Valid @NotNull Sec sec, @Valid @NotNull Storage storage,
		@Valid @NotNull Jobs jobs) {

	/**
	 * SEC EDGAR access settings. SEC requires a declared User-Agent containing a
	 * contact email and limits clients to 10 requests per second.
	 */
	public record Sec(
			@NotBlank @Pattern(regexp = ".*\\S+@\\S+\\.\\S+.*",
					message = "must contain a contact email address as required by SEC") String userAgent,
			@NotNull URI wwwBaseUrl, @NotNull URI dataBaseUrl,
			@Min(1) @Max(10) int maxRequestsPerSecond) {
	}

	public record Storage(@NotNull Path rootPath) {
	}

	public record Jobs(boolean enabled, @NotNull Duration pollInterval, @Min(1) @Max(100) int batchSize,
			@NotNull Duration lockTimeout) {
	}

}
