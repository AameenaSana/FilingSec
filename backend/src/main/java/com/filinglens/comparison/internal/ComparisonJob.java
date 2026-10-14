package com.filinglens.comparison.internal;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.filinglens.comparison.ComparisonCompleted;
import com.filinglens.comparison.internal.ComparisonEngine.SectionResult;
import com.filinglens.comparison.internal.ComparisonRepository.ComparisonRow;
import com.filinglens.comparison.internal.ComparisonViews.Status;
import com.filinglens.document.DocumentContent;
import com.filinglens.document.SectionText;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;

import tools.jackson.databind.json.JsonMapper;

/** Computes and stores the differences for a comparison whose documents are parsed. */
@Component
class ComparisonJob implements JobHandler {

	static final String TYPE = "comparison.compute";

	private static final Logger log = LoggerFactory.getLogger(ComparisonJob.class);

	private final ComparisonRepository comparisons;

	private final DocumentContent documents;

	private final ApplicationEventPublisher events;

	private final JsonMapper json;

	private final Clock clock;

	ComparisonJob(ComparisonRepository comparisons, DocumentContent documents, ApplicationEventPublisher events,
			JsonMapper json, Clock clock) {
		this.comparisons = comparisons;
		this.documents = documents;
		this.events = events;
		this.json = json;
		this.clock = clock;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void handle(Job job) {
		ComparisonRow row = comparisons.find(comparisonId(job)).orElse(null);
		if (row == null || row.status() != Status.COMPUTING) {
			return;
		}
		List<SectionText> base = documents.read(row.baseFilingId());
		List<SectionText> target = documents.read(row.targetFilingId());
		if (base.isEmpty() || target.isEmpty()) {
			comparisons.markFailed(row.id(), "A document has no parsed text to compare", clock.instant());
			return;
		}
		long start = System.nanoTime();
		List<SectionResult> result = ComparisonEngine.compare(base, target);
		comparisons.saveResult(row.id(), result, clock.instant());
		events.publishEvent(new ComparisonCompleted(row.id(), row.baseFilingId(), row.targetFilingId()));
		log.info("Compared filings {} and {} in {} ms ({} sections)", row.baseFilingId(), row.targetFilingId(),
				(System.nanoTime() - start) / 1_000_000, result.size());
	}

	@Override
	public void onPermanentFailure(Job job, Throwable cause) {
		comparisons.markFailed(comparisonId(job), "The comparison could not be computed: " + cause.getMessage(),
				clock.instant());
	}

	private long comparisonId(Job job) {
		return json.readTree(job.payload()).path("comparisonId").asLong();
	}

}
