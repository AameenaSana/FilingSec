package com.filinglens.monitoring.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.filinglens.company.CompanyRef;
import com.filinglens.company.Watchlists;
import com.filinglens.platform.jobs.JobQueue;

/**
 * Queues a poll for each watched company not polled within the poll interval.
 * Each company is polled once per interval however many users watch it.
 */
@Component
@ConditionalOnBooleanProperty("filinglens.monitoring.enabled")
class MonitoringScheduler {

	private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

	private final Watchlists watchlists;

	private final NotificationRepository state;

	private final JobQueue jobs;

	private final MonitoringProperties properties;

	private final Clock clock;

	MonitoringScheduler(Watchlists watchlists, NotificationRepository state, JobQueue jobs,
			MonitoringProperties properties, Clock clock) {
		this.watchlists = watchlists;
		this.state = state;
		this.jobs = jobs;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${filinglens.monitoring.check-interval}",
			initialDelayString = "${filinglens.monitoring.check-interval}")
	void pollDueCompanies() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(properties.pollInterval());
		int queued = 0;
		for (CompanyRef company : watchlists.watchedCompanies()) {
			if (state.claimPoll(company.id(), cutoff, now)) {
				jobs.enqueueUnique(CompanyPollJob.TYPE, Map.of("companyId", company.id(), "cik", company.cik()),
						CompanyPollJob.TYPE + ":" + company.id());
				queued++;
			}
		}
		if (queued > 0) {
			log.info("Queued filing polls for {} watched companies", queued);
		}
	}

}
