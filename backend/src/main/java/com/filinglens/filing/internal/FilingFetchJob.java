package com.filinglens.filing.internal;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.filinglens.edgar.Accession;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.EdgarUrls;
import com.filinglens.edgar.FilingIndexDocument;
import com.filinglens.filing.FilingFetched;
import com.filinglens.filing.internal.FilingRepository.FetchTarget;
import com.filinglens.filing.internal.FilingViews.Status;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;
import com.filinglens.platform.storage.BlobStore;
import com.filinglens.platform.storage.BlobStore.StoredBlob;

import tools.jackson.databind.json.JsonMapper;

/**
 * Downloads a filing's document list and primary document from SEC and stores the
 * original bytes unchanged. Idempotent: a filing already fetched is skipped.
 */
@Component
class FilingFetchJob implements JobHandler {

	static final String TYPE = "filing.fetch";

	private static final Logger log = LoggerFactory.getLogger(FilingFetchJob.class);

	private final EdgarClient edgar;

	private final FilingRepository filings;

	private final BlobStore blobs;

	private final ApplicationEventPublisher events;

	private final JsonMapper json;

	private final Clock clock;

	FilingFetchJob(EdgarClient edgar, FilingRepository filings, BlobStore blobs, ApplicationEventPublisher events,
			JsonMapper json, Clock clock) {
		this.edgar = edgar;
		this.filings = filings;
		this.blobs = blobs;
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
		String accession = accession(job);
		Optional<FetchTarget> found = filings.findFetchTarget(accession);
		if (found.isEmpty() || found.get().status() == Status.FETCHED) {
			return;
		}
		FetchTarget filing = found.get();

		Optional<List<FilingIndexDocument>> index = edgar.fetchFilingIndex(filing.cik(), accession);
		List<FilingIndexDocument> documents = index.filter(docs -> !docs.isEmpty()).orElseGet(() -> fallback(filing));
		if (documents.isEmpty()) {
			filings.markFailed(accession, "SEC lists no documents for this filing", clock.instant());
			return;
		}
		String primary = primaryFilename(filing, documents);
		filings.upsertDocuments(filing, documents, primary);

		Optional<byte[]> content = edgar.fetchArchiveDocument(filing.cik(), accession, primary);
		if (content.isEmpty()) {
			filings.markFailed(accession, "The primary document " + primary + " was not found on SEC", clock.instant());
			return;
		}
		String key = blobKey(filing.cik(), accession, primary);
		StoredBlob blob = blobs.put(key, new ByteArrayInputStream(content.get()));
		filings.recordStoredDocument(filing.id(), primary, blob, contentType(primary), clock.instant());
		filings.markFetched(filing.id(), clock.instant());
		events.publishEvent(new FilingFetched(filing.id(), accession, key));
		log.info("Fetched filing {} ({}, {} bytes)", accession, primary, blob.sizeBytes());
	}

	@Override
	public void onPermanentFailure(Job job, Throwable cause) {
		filings.markFailed(accession(job), "Could not download the filing from SEC: " + cause.getMessage(),
				clock.instant());
	}

	private String accession(Job job) {
		return Accession.normalize(json.readTree(job.payload()).path("accession").asString());
	}

	/** If the index page lists no documents, fall back to the primary document named by the submissions API. */
	private static List<FilingIndexDocument> fallback(FetchTarget filing) {
		if (!EdgarUrls.isValidFilename(filing.primaryDocument())) {
			return List.of();
		}
		return List.of(new FilingIndexDocument(1, filing.primaryDescription(), filing.primaryDocument(),
				filing.formType(), null));
	}

	/** The submissions API's primary document if listed, otherwise sequence 1. */
	static String primaryFilename(FetchTarget filing, List<FilingIndexDocument> documents) {
		return documents.stream()
			.filter(d -> d.filename().equals(filing.primaryDocument()))
			.findFirst()
			.or(() -> documents.stream().filter(d -> d.sequence() == 1).findFirst())
			.orElse(documents.getFirst())
			.filename();
	}

	static String blobKey(String cik, String accession, String filename) {
		return "edgar/" + cik + "/" + Accession.withoutDashes(accession) + "/" + filename;
	}

	static String contentType(String filename) {
		String lower = filename.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".htm") || lower.endsWith(".html")) {
			return "text/html";
		}
		if (lower.endsWith(".txt")) {
			return "text/plain";
		}
		if (lower.endsWith(".xml")) {
			return "application/xml";
		}
		if (lower.endsWith(".pdf")) {
			return "application/pdf";
		}
		return "application/octet-stream";
	}

}
