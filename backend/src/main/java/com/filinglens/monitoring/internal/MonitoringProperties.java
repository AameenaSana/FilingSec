package com.filinglens.monitoring.internal;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled whether watched companies are polled
 * @param pollInterval how often each watched company's filing list is re-read
 * from SEC
 * @param checkInterval how often the scheduler looks for companies due a poll
 * @param autoCompare whether new 10-K and 10-Q filings are compared with the
 * previous period automatically
 * @param maxFilingsPerDiscovery the most recent new filings acted on at once, so a
 * long gap in polling does not flood watchers
 */
@Validated
@ConfigurationProperties(prefix = "filinglens.monitoring")
record MonitoringProperties(boolean enabled, @NotNull Duration pollInterval, @NotNull Duration checkInterval,
		boolean autoCompare, @Min(1) @Max(100) int maxFilingsPerDiscovery) {
}
