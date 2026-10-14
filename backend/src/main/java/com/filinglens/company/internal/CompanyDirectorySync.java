package com.filinglens.company.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.filinglens.edgar.CompanyTickerEntry;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;
import com.filinglens.platform.jobs.JobQueue;

/**
 * Keeps the company directory in sync with SEC's ticker file: once on first
 * startup (empty directory) and daily thereafter, as a background job.
 */
@Component
class CompanyDirectorySync implements JobHandler {

	static final String JOB_TYPE = "company.directory-sync";

	/** Below this many entries the download is treated as truncated and nothing is removed. */
	static final int MIN_PLAUSIBLE_ENTRIES = 1_000;

	private static final Logger log = LoggerFactory.getLogger(CompanyDirectorySync.class);

	private final EdgarClient edgar;

	private final CompanyRepository companies;

	private final Clock clock;

	CompanyDirectorySync(EdgarClient edgar, CompanyRepository companies, Clock clock) {
		this.edgar = edgar;
		this.companies = companies;
		this.clock = clock;
	}

	@Override
	public String type() {
		return JOB_TYPE;
	}

	@Override
	public void handle(Job job) {
		List<CompanyTickerEntry> entries = edgar.fetchCompanyTickers();
		boolean plausible = entries.size() >= MIN_PLAUSIBLE_ENTRIES;
		if (!plausible) {
			log.warn("SEC ticker directory has only {} entries; keeping existing tickers", entries.size());
		}
		int applied = companies.applyDirectory(entries, clock.instant(), plausible);
		log.info("Company directory synced: {} tickers", applied);
	}

	@Component
	@ConditionalOnBooleanProperty("filinglens.company.directory-sync.enabled")
	static class Scheduler {

		private final JobQueue jobs;

		private final CompanyRepository companies;

		Scheduler(JobQueue jobs, CompanyRepository companies) {
			this.jobs = jobs;
			this.companies = companies;
		}

		@EventListener(ApplicationReadyEvent.class)
		void syncOnFirstStartup() {
			if (companies.count() == 0) {
				log.info("Company directory is empty; queuing initial sync from SEC");
				enqueue();
			}
		}

		@Scheduled(cron = "${filinglens.company.directory-sync.cron}", zone = "UTC")
		void enqueue() {
			jobs.enqueueUnique(JOB_TYPE, Map.of(), JOB_TYPE);
		}

	}

}
