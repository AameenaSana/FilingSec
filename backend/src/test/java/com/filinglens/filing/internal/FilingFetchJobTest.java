package com.filinglens.filing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.FilingIndexDocument;
import com.filinglens.filing.FilingFetched;
import com.filinglens.filing.internal.FilingRepository.FetchTarget;
import com.filinglens.filing.internal.FilingViews.Status;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.storage.BlobStore;
import com.filinglens.platform.storage.BlobStore.StoredBlob;

import tools.jackson.databind.json.JsonMapper;

class FilingFetchJobTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final String ACCESSION = "0000320193-25-000079";

	static final Job JOB = new Job(1, FilingFetchJob.TYPE, "{\"accession\":\"" + ACCESSION + "\"}", 1, 5);

	static final FetchTarget TARGET = new FetchTarget(11, ACCESSION, "0000320193", "10-K", "aapl-20250927.htm", "10-K",
			Status.FETCHING);

	final EdgarClient edgar = mock(EdgarClient.class);

	final FilingRepository filings = mock(FilingRepository.class);

	final BlobStore blobs = mock(BlobStore.class);

	final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	final FilingFetchJob job = new FilingFetchJob(edgar, filings, blobs, events, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC));

	static final List<FilingIndexDocument> INDEX = List.of(
			new FilingIndexDocument(1, "10-K", "aapl-20250927.htm", "10-K", 100L),
			new FilingIndexDocument(2, "EX-21.1", "ex21.htm", "EX-21.1", 50L));

	@Test
	void storesPrimaryDocumentAndPublishesEvent() {
		when(filings.findFetchTarget(ACCESSION)).thenReturn(Optional.of(TARGET));
		when(edgar.fetchFilingIndex("0000320193", ACCESSION)).thenReturn(Optional.of(INDEX));
		when(edgar.fetchArchiveDocument("0000320193", ACCESSION, "aapl-20250927.htm"))
			.thenReturn(Optional.of("<html>10-K</html>".getBytes()));
		String key = "edgar/0000320193/000032019325000079/aapl-20250927.htm";
		StoredBlob blob = new StoredBlob(key, "ab".repeat(32), 17);
		when(blobs.put(eq(key), any(InputStream.class))).thenReturn(blob);

		job.handle(JOB);

		verify(filings).upsertDocuments(TARGET, INDEX, "aapl-20250927.htm");
		verify(filings).recordStoredDocument(11, "aapl-20250927.htm", blob, "text/html", NOW);
		verify(filings).markFetched(11, NOW);
		verify(events).publishEvent(new FilingFetched(11, ACCESSION, key));
	}

	@Test
	void fallsBackToSubmissionsPrimaryDocumentWhenIndexIsUnavailable() {
		when(filings.findFetchTarget(ACCESSION)).thenReturn(Optional.of(TARGET));
		when(edgar.fetchFilingIndex(anyString(), anyString())).thenReturn(Optional.empty());
		when(edgar.fetchArchiveDocument(anyString(), anyString(), anyString())).thenReturn(Optional.of(new byte[1]));
		when(blobs.put(anyString(), any())).thenReturn(new StoredBlob("k", "s", 1));

		job.handle(JOB);

		verify(filings).upsertDocuments(eq(TARGET),
				argThat(docs -> docs.size() == 1 && docs.getFirst().filename().equals("aapl-20250927.htm")),
				eq("aapl-20250927.htm"));
		verify(filings).markFetched(11, NOW);
	}

	@Test
	void missingPrimaryDocumentMarksFilingFailedWithoutRetry() {
		when(filings.findFetchTarget(ACCESSION)).thenReturn(Optional.of(TARGET));
		when(edgar.fetchFilingIndex(anyString(), anyString())).thenReturn(Optional.of(INDEX));
		when(edgar.fetchArchiveDocument(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

		job.handle(JOB);

		verify(filings).markFailed(eq(ACCESSION), argThat(m -> m.contains("aapl-20250927.htm")), eq(NOW));
		verify(filings, never()).markFetched(11, NOW);
		verifyNoInteractions(blobs, events);
	}

	@Test
	void alreadyFetchedFilingIsSkipped() {
		when(filings.findFetchTarget(ACCESSION))
			.thenReturn(Optional.of(new FetchTarget(11, ACCESSION, "0000320193", "10-K", "a.htm", null, Status.FETCHED)));

		job.handle(JOB);

		verifyNoInteractions(edgar, blobs, events);
	}

	@Test
	void permanentFailureIsRecordedOnTheFiling() {
		job.onPermanentFailure(JOB, new IllegalStateException("SEC EDGAR is unavailable"));

		verify(filings).markFailed(eq(ACCESSION), argThat(m -> m.contains("SEC EDGAR is unavailable")), eq(NOW));
	}

	@Test
	void primaryDocumentPrefersSubmissionsNameThenSequenceOne() {
		var other = new FetchTarget(1, ACCESSION, "0000320193", "8-K", "not-listed.htm", null, Status.FETCHING);
		assertThat(FilingFetchJob.primaryFilename(TARGET, INDEX)).isEqualTo("aapl-20250927.htm");
		assertThat(FilingFetchJob.primaryFilename(other, INDEX)).isEqualTo("aapl-20250927.htm");
		assertThat(FilingFetchJob.contentType("x.HTM")).isEqualTo("text/html");
		assertThat(FilingFetchJob.contentType("x.txt")).isEqualTo("text/plain");
	}

}
