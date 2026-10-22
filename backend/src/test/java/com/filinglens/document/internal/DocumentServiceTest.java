package com.filinglens.document.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.filinglens.document.internal.DocumentViews.SectionView;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.jobs.JobQueue;
import com.filinglens.platform.storage.BlobStore;

class DocumentServiceTest {

	static final String ACCESSION = "0000320193-25-000079";

	final FilingCatalog filings = mock(FilingCatalog.class);

	final DocumentRepository documents = mock(DocumentRepository.class);

	final DocumentService service = new DocumentService(filings, documents, mock(JobQueue.class), mock(BlobStore.class),
			Clock.systemUTC());

	@BeforeEach
	void setUp() {
		when(filings.findByAccession(ACCESSION)).thenReturn(Optional.of(new FilingRef(11, 1, ACCESSION, "0000320193",
				"Apple Inc.", "10-K", null, null, null, null, "FETCHED")));
	}

	@Test
	void unknownSectionOfAParsedDocumentIsNotFound() {
		when(documents.sections(11)).thenReturn(
				List.of(new SectionView("10K.ITEM_1A", "Risk Factors", "Item 1A. Risk Factors", 3, 100, 206, "HIGH")));

		assertThatThrownBy(() -> service.blocks(ACCESSION, "ITEM_1A", 1, 10)).isInstanceOf(NotFoundException.class);
		assertThat(service.blocks(ACCESSION, "10K.ITEM_1A", 1, 10)).isEmpty();
	}

	@Test
	void documentNotParsedYetHasNoBlocksRatherThanAnError() {
		when(documents.sections(11)).thenReturn(List.of());

		assertThat(service.blocks(ACCESSION, "10K.ITEM_1A", 1, 10)).isEmpty();
	}

}
