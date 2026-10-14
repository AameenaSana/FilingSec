package com.filinglens.filing.internal;

import java.time.Clock;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filinglens.edgar.Accession;
import com.filinglens.filing.internal.FilingViews.FilingDetail;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.jobs.JobQueue;

@Service
class FilingService {

	private final FilingRepository filings;

	private final JobQueue jobs;

	private final Clock clock;

	FilingService(FilingRepository filings, JobQueue jobs, Clock clock) {
		this.filings = filings;
		this.jobs = jobs;
		this.clock = clock;
	}

	FilingDetail get(String accession) {
		String normalized = Accession.normalize(accession);
		return filings.findDetail(normalized).orElseThrow(() -> new NotFoundException("Filing", normalized));
	}

	/**
	 * Queues download of the filing's documents unless they are already fetched or
	 * being fetched. A failed fetch can be retried this way.
	 */
	@Transactional
	FilingDetail requestFetch(String accession) {
		String normalized = Accession.normalize(accession);
		get(normalized);
		if (filings.markFetching(normalized, clock.instant())) {
			jobs.enqueueUnique(FilingFetchJob.TYPE, Map.of("accession", normalized), FilingFetchJob.TYPE + ":" + normalized);
		}
		return get(normalized);
	}

}
