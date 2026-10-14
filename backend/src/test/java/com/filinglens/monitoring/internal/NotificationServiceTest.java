package com.filinglens.monitoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.filinglens.company.Watchlists;
import com.filinglens.company.Watchlists.Watcher;
import com.filinglens.comparison.ComparisonCompleted;
import com.filinglens.comparison.ComparisonRequests;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.filing.FilingsDiscovered;
import com.filinglens.monitoring.internal.NotificationRepository.NewNotification;
import com.filinglens.monitoring.internal.NotificationViews.NotificationView;
import com.filinglens.monitoring.internal.NotificationViews.Type;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.jobs.JobQueue;

class NotificationServiceTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final UUID ALICE = UUID.randomUUID();

	static final UUID BOB = UUID.randomUUID();

	static final UUID CAROL = UUID.randomUUID();

	static final FilingRef TEN_K = filing(42, "0000320193-25-000079", "10-K", LocalDate.of(2025, 9, 27));

	static final FilingRef PRIOR_TEN_K = filing(41, "0000320193-24-000123", "10-K", LocalDate.of(2024, 9, 28));

	NotificationRepository repository = mock(NotificationRepository.class);

	Watchlists watchlists = mock(Watchlists.class);

	FilingCatalog filings = mock(FilingCatalog.class);

	ComparisonRequests comparisons = mock(ComparisonRequests.class);

	NotificationStream stream = mock(NotificationStream.class);

	JobQueue jobs = mock(JobQueue.class);

	NotificationService service;

	@BeforeEach
	void setUp() {
		service = new NotificationService(repository, watchlists, filings, comparisons, stream, jobs,
				new MonitoringProperties(true, Duration.ofMinutes(15), Duration.ofMinutes(1), true, 10),
				Clock.fixed(NOW, ZoneOffset.UTC));
		when(filings.findById(42)).thenReturn(Optional.of(TEN_K));
		when(filings.findById(41)).thenReturn(Optional.of(PRIOR_TEN_K));
		when(repository.insert(any(), eq(NOW))).thenAnswer(inv -> Optional.of(new NotificationView(9, Type.NEW_FILING,
				"t", "b", "/l", NOW, false)));
		when(repository.emailEnabled(any())).thenReturn(true);
		when(watchlists.watchers(1)).thenReturn(List.of(new Watcher(ALICE, List.of("10-K", "10-Q"), true),
				new Watcher(BOB, List.of("8-K"), true), new Watcher(CAROL, List.of("10-K"), false)));
	}

	@Test
	void newFilingNotifiesWatchersOfThatFormAndIsComparedWithThePreviousPeriod() {
		when(comparisons.compareWithPrevious(42)).thenReturn(Optional.of(7L));

		service.on(new FilingsDiscovered(1, "0000320193", List.of(42L)));

		var notification = ArgumentCaptor.forClass(NewNotification.class);
		verify(repository).insert(notification.capture(), eq(NOW));
		assertThat(notification.getValue()).extracting(NewNotification::userId, NewNotification::type,
				NewNotification::link)
			.containsExactly(ALICE, Type.NEW_FILING, "/filings/0000320193-25-000079");
		assertThat(notification.getValue().title()).isEqualTo("Apple Inc. filed a 10-K");
		assertThat(notification.getValue().body())
			.isEqualTo("Apple Inc. filed a 10-K for the period ended Sep 27, 2025 on Oct 31, 2025.");
		verify(jobs).enqueue(NotificationEmailJob.TYPE, Map.of("notificationId", 9L));
		// Carol watches 10-Ks with notifications off; she still gets the prepared filing.
		verify(filings).requestDocuments(42);
		verify(repository).recordMonitoredComparison(7, 42, NOW);
	}

	@Test
	void noEmailWhenTheUserTurnedItOffAndNoDuplicateWork() {
		when(repository.emailEnabled(ALICE)).thenReturn(false);
		when(repository.insert(any(), eq(NOW))).thenReturn(Optional.empty(),
				Optional.of(new NotificationView(9, Type.NEW_FILING, "t", "b", "/l", NOW, false)));

		service.on(new FilingsDiscovered(1, "0000320193", List.of(42L)));
		service.on(new FilingsDiscovered(1, "0000320193", List.of(42L)));

		verify(jobs, never()).enqueue(any(), any());
	}

	@Test
	void eightKsAndAmendmentsAreNotAutoCompared() {
		FilingRef eightK = filing(43, "0000320193-25-000080", "8-K", null);
		when(filings.findById(43)).thenReturn(Optional.of(eightK));

		service.on(new FilingsDiscovered(1, "0000320193", List.of(43L)));

		verify(filings).requestDocuments(43);
		verify(comparisons, never()).compareWithPrevious(anyLong());
	}

	@Test
	void comparisonFailureDoesNotBlockNotifications() {
		when(comparisons.compareWithPrevious(42)).thenThrow(new IllegalStateException("boom"));

		service.on(new FilingsDiscovered(1, "0000320193", List.of(42L)));

		verify(repository).insert(any(), eq(NOW));
	}

	@Test
	void unwatchedCompanyIsIgnored() {
		when(watchlists.watchers(2)).thenReturn(List.of());

		service.on(new FilingsDiscovered(2, "0000789019", List.of(42L)));

		verify(filings, never()).requestDocuments(anyLong());
	}

	@Test
	void onlyAutomaticComparisonsAreAnnounced() {
		when(repository.monitoredFiling(7)).thenReturn(Optional.of(42L));
		when(repository.monitoredFiling(8)).thenReturn(Optional.empty());

		service.on(new ComparisonCompleted(8, 41, 42));
		verify(repository, never()).insert(any(), any());

		service.on(new ComparisonCompleted(7, 41, 42));
		var notification = ArgumentCaptor.forClass(NewNotification.class);
		verify(repository, times(1)).insert(notification.capture(), eq(NOW));
		assertThat(notification.getValue()).extracting(NewNotification::userId, NewNotification::type,
				NewNotification::comparisonId, NewNotification::link)
			.containsExactly(ALICE, Type.COMPARISON_READY, 7L, "/compare/7");
		assertThat(notification.getValue().body()).contains("period ended Sep 27, 2025")
			.contains("period ended Sep 28, 2024");
	}

	@Test
	void markingAnotherUsersNotificationIsNotFound() {
		when(repository.markRead(ALICE, 5, NOW)).thenReturn(false);

		assertThatThrownBy(() -> service.markRead(ALICE, 5)).isInstanceOf(NotFoundException.class);
		verify(stream, never()).publishUnread(any(), anyInt());
	}

	static FilingRef filing(long id, String accession, String form, LocalDate period) {
		return new FilingRef(id, 1, accession, "0000320193", "Apple Inc.", form, LocalDate.of(2025, 10, 31), period,
				"doc.htm", null, "DISCOVERED");
	}

}
