package com.filinglens.monitoring.internal;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.filinglens.filing.FilingCatalog;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Re-reads one watched company's recent filings from SEC. New filings are
 * announced by the filing module and handled by {@link NotificationService}.
 * Requests go through the shared EDGAR client, so they respect SEC's rate limit.
 */
@Component
class CompanyPollJob implements JobHandler {

	static final String TYPE = "monitoring.poll";

	private final FilingCatalog filings;

	private final NotificationRepository state;

	private final JsonMapper json;

	private final Clock clock;

	CompanyPollJob(FilingCatalog filings, NotificationRepository state, JsonMapper json, Clock clock) {
		this.filings = filings;
		this.state = state;
		this.json = json;
		this.clock = clock;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void handle(Job job) {
		JsonNode payload = json.readTree(job.payload());
		filings.refreshRecent(payload.path("cik").asString());
		state.recordPollResult(payload.path("companyId").asLong(), null, clock.instant());
	}

	@Override
	public void onPermanentFailure(Job job, Throwable cause) {
		JsonNode payload = json.readTree(job.payload());
		state.recordPollResult(payload.path("companyId").asLong(), String.valueOf(cause.getMessage()),
				clock.instant());
	}

}
