package com.filinglens.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.filinglens.document.DocumentParsed;
import com.filinglens.document.internal.SectionDetector.DetectedSection;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.storage.BlobStore;

import tools.jackson.databind.json.JsonMapper;

class DocumentParseJobTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final Job JOB = new Job(1, DocumentParseJob.TYPE, "{\"filingId\":11}", 1, 5);

	final FilingCatalog filings = mock(FilingCatalog.class);

	final DocumentRepository documents = mock(DocumentRepository.class);

	final BlobStore blobs = mock(BlobStore.class);

	final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	final DocumentParseJob job = new DocumentParseJob(filings, documents, blobs, events, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC));

	static FilingRef filing(String blobKey) {
		return new FilingRef(11, 7, "0000320193-25-000079", "0000320193", "Apple Inc.", "8-K",
				LocalDate.of(2025, 10, 31), null, "doc.htm", blobKey, "FETCHED");
	}

	@Test
	@SuppressWarnings("unchecked")
	void parsesStoresViewerCopyAndPublishesEvent() {
		String key = "edgar/0000320193/000032019325000079/doc.htm";
		when(filings.findById(11)).thenReturn(Optional.of(filing(key)));
		String html = "<p>Item 2.02 Results of Operations and Financial Condition.</p><p>"
				+ "Revenue grew strongly in the quarter. ".repeat(20) + "</p>";
		when(blobs.open(key)).thenReturn(Optional.of(new ByteArrayInputStream(html.getBytes())));

		job.handle(JOB);

		verify(blobs).put(eq("processed/0000320193/000032019325000079/v1/annotated.html"), any(InputStream.class));
		ArgumentCaptor<List<DetectedSection>> sections = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<List<ParsedBlock>> blocks = ArgumentCaptor.forClass(List.class);
		verify(documents).saveParsed(eq(11L), eq(DocumentParseJob.PARSER_VERSION), sections.capture(),
				blocks.capture(), eq("processed/0000320193/000032019325000079/v1/annotated.html"), eq(NOW));
		assertThat(sections.getValue()).extracting(DetectedSection::key).containsExactly("8K.ITEM_2.02");
		assertThat(blocks.getValue()).hasSize(2);
		verify(events).publishEvent(new DocumentParsed(11, "0000320193-25-000079", DocumentParseJob.PARSER_VERSION));
	}

	@Test
	void unsupportedFormatsAreMarkedWithoutFailing() {
		when(filings.findById(11)).thenReturn(Optional.of(filing("edgar/0000320193/000032019325000079/doc.pdf")));
		when(blobs.open(anyString())).thenReturn(Optional.of(new ByteArrayInputStream(new byte[] { 1 })));

		job.handle(JOB);

		verify(documents).markFinished(eq(11L), eq("UNSUPPORTED"), argThat(d -> d.contains(".pdf")), eq(NOW));
		verify(documents, never()).saveParsed(any(Long.class), any(Integer.class), anyList(), anyList(), any(), any());
	}

	@Test
	void documentWithoutTextFails() {
		when(filings.findById(11)).thenReturn(Optional.of(filing("edgar/0000320193/000032019325000079/doc.htm")));
		when(blobs.open(anyString())).thenReturn(Optional.of(new ByteArrayInputStream("<html><body> </body></html>".getBytes())));

		job.handle(JOB);

		verify(documents).markFinished(eq(11L), eq("FAILED"), argThat(d -> d.contains("No text")), eq(NOW));
	}

	@Test
	void permanentFailureIsRecorded() {
		job.onPermanentFailure(JOB, new IllegalStateException("disk error"));

		verify(documents).markFinished(eq(11L), eq("FAILED"), argThat(d -> d.contains("disk error")), eq(NOW));
	}

}
