package com.filinglens.filing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.filinglens.company.CompanyDirectory;
import com.filinglens.company.CompanyRef;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.edgar.FilingRecord;
import com.filinglens.filing.FilingsDiscovered;
import com.filinglens.filing.internal.FilingRepository.SyncState;
import com.filinglens.filing.internal.FilingViews.FilingPage;
import com.filinglens.filing.internal.FilingViews.FilingSummary;
import com.filinglens.filing.internal.FilingViews.Status;
import com.filinglens.platform.error.InvalidRequestException;
import com.filinglens.platform.jobs.JobQueue;

class FilingDiscoveryServiceTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final CompanyRef APPLE = new CompanyRef(7, "0000320193", "Apple Inc.");

	final CompanyDirectory companies = mock(CompanyDirectory.class);

	final EdgarClient edgar = mock(EdgarClient.class);

	final FilingRepository filings = mock(FilingRepository.class);

	final JobQueue jobs = mock(JobQueue.class);

	final TransactionTemplate transactions = mock(TransactionTemplate.class);

	final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	FilingDiscoveryService service;

	@BeforeEach
	void setUp() {
		when(companies.resolve("320193")).thenReturn(APPLE);
		// Run transaction callbacks inline.
		doAnswer(inv -> {
			inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
			return null;
		}).when(transactions).executeWithoutResult(any());
		service = new FilingDiscoveryService(companies, edgar, filings, jobs, transactions,
				new FilingProperties(Duration.ofHours(1)), events, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void freshListIsServedWithoutCallingSec() {
		when(filings.syncState(7)).thenReturn(new SyncState(NOW.minusSeconds(60), Set.of()));
		when(filings.page(eq(7L), eq("0000320193"), any(), any(), anyInt())).thenReturn(List.of());

		FilingPage page = service.list("320193", List.of("10-K"), null, 25);

		assertThat(page.stale()).isFalse();
		verifyNoInteractions(edgar);
		verify(filings).page(7L, "0000320193", List.of("10-K", "10-K/A"), null, 26);
	}

	@Test
	void staleListIsRefreshedAndOnlyTrackedFormsAreStored() {
		when(filings.syncState(7)).thenReturn(new SyncState(null, Set.of()), new SyncState(null, Set.of()),
				new SyncState(NOW, Set.of("CIK0000320193-submissions-001.json")));
		FilingRecord tenK = record("0000320193-25-000079", "10-K");
		FilingRecord form4 = record("0001140361-25-040001", "4");
		when(edgar.fetchSubmissions("0000320193")).thenReturn(Optional.of(submissions(List.of(tenK, form4),
				List.of("CIK0000320193-submissions-001.json", "CIK0000320193-submissions-002.json"))));

		service.list("320193", null, null, 25);

		verify(filings).insertNew(7, List.of(tenK), NOW);
		verify(filings).markRecentSynced(7, NOW);
		verify(jobs).enqueueUnique(eq(FilingHistoryImportJob.TYPE),
				argThat(payload -> ((Map<?, ?>) payload).get("files")
					.equals(List.of("CIK0000320193-submissions-002.json"))),
				eq(FilingHistoryImportJob.TYPE + ":7"));
		// A company's first sync finds its history, not news.
		verify(events, never()).publishEvent(any(Object.class));
	}

	@Test
	void filingsNewSinceThePreviousSyncAreAnnounced() {
		when(filings.syncState(7)).thenReturn(new SyncState(NOW.minus(Duration.ofDays(1)), Set.of()));
		FilingRecord known = record("0000320193-25-000071", "10-Q");
		FilingRecord fresh = record("0000320193-25-000079", "10-K");
		when(edgar.fetchSubmissions("0000320193"))
			.thenReturn(Optional.of(submissions(List.of(fresh, known), List.of())));
		when(filings.knownAccessions(List.of("0000320193-25-000079", "0000320193-25-000071")))
			.thenReturn(Set.of("0000320193-25-000071"));
		when(filings.idsOf(List.of("0000320193-25-000079"))).thenReturn(List.of(42L));

		service.list("320193", null, null, 25);

		verify(events).publishEvent(new FilingsDiscovered(7, "0000320193", List.of(42L)));
	}

	@Test
	void storedListIsServedAsStaleWhenSecIsDown() {
		when(filings.syncState(7)).thenReturn(new SyncState(NOW.minus(Duration.ofDays(2)), Set.of()));
		when(edgar.fetchSubmissions(any())).thenThrow(new EdgarUnavailableException("down"));

		assertThat(service.list("320193", null, null, 25).stale()).isTrue();
		verify(filings, never()).markRecentSynced(anyLong(), any());
	}

	@Test
	void neverSyncedCompanyFailsWhenSecIsDown() {
		when(filings.syncState(7)).thenReturn(new SyncState(null, Set.of()));
		when(edgar.fetchSubmissions(any())).thenThrow(new EdgarUnavailableException("down"));

		assertThatThrownBy(() -> service.list("320193", null, null, 25))
			.isInstanceOf(EdgarUnavailableException.class);
	}

	@Test
	void pagesUsingAccessionCursor() {
		when(filings.syncState(7)).thenReturn(new SyncState(NOW, Set.of()));
		List<FilingSummary> rows = List.of(summary("0000320193-25-000079"), summary("0000320193-25-000071"),
				summary("0000320193-25-000057"));
		when(filings.page(eq(7L), any(), any(), any(), eq(3))).thenReturn(rows);

		FilingPage page = service.list("320193", null, null, 2);

		assertThat(page.items()).hasSize(2);
		assertThat(CursorCodec.decode(page.nextCursor())).isEqualTo("0000320193-25-000071");

		assertThatThrownBy(() -> service.list("320193", null, "not-a-cursor", 2))
			.isInstanceOf(InvalidRequestException.class);
		verify(jobs, never()).enqueueUnique(any(), anyMap(), any());
	}

	static FilingRecord record(String accession, String form) {
		return new FilingRecord(accession, form, LocalDate.of(2025, 10, 31), null, null, "doc.htm", null, null, null,
				false, false, null, null);
	}

	static FilingSummary summary(String accession) {
		return new FilingSummary(accession, "10-K", LocalDate.of(2025, 1, 1), null, null, null, List.of(), null,
				Status.DISCOVERED);
	}

	static CompanySubmissions submissions(List<FilingRecord> recent, List<String> history) {
		return new CompanySubmissions("0000320193", "Apple Inc.", null, null, null, null, null, null, List.of(),
				List.of(), recent, history);
	}

}
