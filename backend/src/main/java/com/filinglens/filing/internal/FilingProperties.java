package com.filinglens.filing.internal;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param listMaxAge how long a company's filing list is served before recent
 * filings are re-read from EDGAR
 */
@Validated
@ConfigurationProperties(prefix = "filinglens.filing")
record FilingProperties(@NotNull Duration listMaxAge) {
}
