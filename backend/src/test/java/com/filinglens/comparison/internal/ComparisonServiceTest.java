package com.filinglens.comparison.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.filinglens.comparison.internal.ComparisonRepository.ComparisonRow;
import com.filinglens.comparison.internal.ComparisonViews.Status;
import com.filinglens.document.DocumentContent;
import com.filinglens.document.DocumentContent.State;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.error.InvalidRequestException;
import com.filinglens.platform.jobs.JobQueue;

class ComparisonServiceTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final FilingRef K2024 = filing(1, "0000320193-24-000123", "10-K", "2024-09-28");

	static final FilingRef K2025 = filing(2, "0000320193-25-000079", "10-K", "2025-09-27");

	final ComparisonRepository repository = mock(ComparisonRepository.class);

	final FilingCatalog filings = mock(FilingCatalog.class);

	final DocumentContent documents = mock(DocumentContent.class);

	final JobQueue jobs = mock(JobQueue.class);

	final ComparisonService service = new ComparisonService(repository, filings, documents, jobs,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@BeforeEach
	void setUp() {
		for (FilingRef f : List.of(K2024, K2025)) {
			when(filings.findByAccession(f.accessionNumber())).thenReturn(Optional.of(f));
			when(filings.findById(f.id())).thenReturn(Optional.of(f));
		}
		when(repository.findOrCreate(anyLong(), anyLong(), eq(ComparisonEngine.ALGORITHM_VERSION), any())).thenReturn(9L);
	}

	@Test
	void earlierFilingBecomesTheBaseRegardlessOfOrder() {
		when(repository.find(9)).thenReturn(Optional.of(row(Status.WAITING)));
		when(documents.state(anyLong())).thenReturn(State.PENDING);

		service.create(K2025.accessionNumber(), K2024.accessionNumber());

		verify(repository).findOrCreate(1, 2, ComparisonEngine.ALGORITHM_VERSION, NOW);
	}

	@Test
	void rejectsFilingsThatCannotBeCompared() {
		FilingRef q = filing(3, "0000320193-25-000057", "10-Q", "2025-06-28");
		FilingRef other = new FilingRef(4, 99, "0000789019-25-000010", "0000789019", "Microsoft", "10-K",
				LocalDate.parse("2025-07-30"), LocalDate.parse("2025-06-30"), "a.htm", null, "DISCOVERED");
		when(filings.findByAccession(q.accessionNumber())).thenReturn(Optional.of(q));
		when(filings.findByAccession(other.accessionNumber())).thenReturn(Optional.of(other));

		assertThatThrownBy(() -> service.create(K2025.accessionNumber(), q.accessionNumber()))
			.isInstanceOf(InvalidRequestException.class)
			.extracting("code")
			.isEqualTo("not-comparable");
		assertThatThrownBy(() -> service.create(K2025.accessionNumber(), other.accessionNumber()))
			.extracting("code")
			.isEqualTo("not-comparable");
		assertThatThrownBy(() -> service.create(K2025.accessionNumber(), K2025.accessionNumber()))
			.extracting("code")
			.isEqualTo("same-filing");
	}

	@Test
	void startsComputingWhenBothDocumentsAreParsed() {
		when(repository.find(9)).thenReturn(Optional.of(row(Status.WAITING)));
		when(documents.state(anyLong())).thenReturn(State.PARSED);
		when(repository.transition(9, Status.WAITING, Status.COMPUTING, null, NOW)).thenReturn(true);

		service.advance(9);

		verify(jobs).enqueueUnique(eq(ComparisonJob.TYPE), anyMap(), eq(ComparisonJob.TYPE + ":9"));
	}

	@Test
	void preparesDocumentsThatAreNotParsedYet() {
		when(repository.find(9)).thenReturn(Optional.of(row(Status.WAITING)));
		when(documents.state(1)).thenReturn(State.PARSED);
		when(documents.state(2)).thenReturn(State.PENDING);

		service.advance(9);

		verify(documents).prepare(2);
		verify(documents, never()).prepare(1);
		verify(jobs, never()).enqueueUnique(anyString(), anyMap(), anyString());
	}

	@Test
	void failsWhenADocumentCannotBeRead() {
		when(repository.find(9)).thenReturn(Optional.of(row(Status.WAITING)));
		when(documents.state(1)).thenReturn(State.UNAVAILABLE);
		when(documents.state(2)).thenReturn(State.PARSED);

		service.advance(9);

		verify(repository).transition(eq(9L), eq(Status.WAITING), eq(Status.FAILED), eq(
				"The earlier filing's document could not be downloaded or read"), eq(NOW));
	}

	@Test
	void suggestsPreviousAnnualReportPreferringOriginalsOverAmendments() {
		FilingRef amendment = filing(5, "0000320193-25-000001", "10-K/A", "2024-09-28");
		FilingRef k2023 = filing(6, "0000320193-23-000106", "10-K", "2023-09-30");
		when(filings.comparableFilings(2, 24)).thenReturn(List.of(amendment, K2024, k2023));

		assertThat(service.candidates(K2025.accessionNumber()).suggested()).isEqualTo(K2024.accessionNumber());
	}

	@Test
	void suggestsSameQuarterOfThePreviousYearForTenQ() {
		FilingRef q3 = filing(10, "0000320193-25-000073", "10-Q", "2025-06-28");
		FilingRef q2 = filing(11, "0000320193-25-000057", "10-Q", "2025-03-29");
		FilingRef q3LastYear = filing(12, "0000320193-24-000081", "10-Q", "2024-06-29");
		when(filings.findByAccession(q3.accessionNumber())).thenReturn(Optional.of(q3));
		when(filings.comparableFilings(10, 24)).thenReturn(List.of(q2, q3LastYear));

		assertThat(service.candidates(q3.accessionNumber()).suggested()).isEqualTo(q3LastYear.accessionNumber());
	}

	static FilingRef filing(long id, String accession, String form, String period) {
		LocalDate date = LocalDate.parse(period);
		return new FilingRef(id, 7, accession, "0000320193", "Apple Inc.", form, date.plusDays(35), date, "a.htm", null,
				"DISCOVERED");
	}

	static ComparisonRow row(Status status) {
		return new ComparisonRow(9, 1, 2, status, null, null, null);
	}

}
