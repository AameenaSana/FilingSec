package com.filinglens.company.internal;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param profileMaxAge how long company details from EDGAR are served before they
 * are refreshed on the next view
 */
@Validated
@ConfigurationProperties(prefix = "filinglens.company")
record CompanyProperties(@NotNull Duration profileMaxAge, @Valid @NotNull DirectorySync directorySync) {

	/**
	 * @param enabled whether the ticker directory is synced on a schedule and on first
	 * startup
	 * @param cron when the daily sync is queued (UTC)
	 */
	record DirectorySync(boolean enabled, @NotBlank String cron) {
	}

}
