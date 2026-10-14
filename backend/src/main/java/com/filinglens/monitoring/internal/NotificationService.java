package com.filinglens.monitoring.internal;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.filinglens.company.Watchlists;
import com.filinglens.company.Watchlists.Watcher;
import com.filinglens.comparison.ComparisonCompleted;
import com.filinglens.comparison.ComparisonRequests;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.filing.FilingsDiscovered;
import com.filinglens.monitoring.internal.NotificationRepository.NewNotification;
import com.filinglens.monitoring.internal.NotificationViews.NotificationPage;
import com.filinglens.monitoring.internal.NotificationViews.NotificationView;
import com.filinglens.monitoring.internal.NotificationViews.Preferences;
import com.filinglens.monitoring.internal.NotificationViews.Type;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.jobs.JobQueue;

/**
 * Turns newly discovered filings and completed automatic comparisons into
 * notifications for the users watching the company, and prepares new filings so
 * they are ready to read, compare and question.
 */
@Service
class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

	private static final List<String> PERIODIC_FORMS = List.of("10-K", "10-Q");

	private final NotificationRepository notifications;

	private final Watchlists watchlists;

	private final FilingCatalog filings;

	private final ComparisonRequests comparisons;

	private final NotificationStream stream;

	private final JobQueue jobs;

	private final MonitoringProperties properties;

	private final Clock clock;

	NotificationService(NotificationRepository notifications, Watchlists watchlists, FilingCatalog filings,
			ComparisonRequests comparisons, NotificationStream stream, JobQueue jobs, MonitoringProperties properties,
			Clock clock) {
		this.notifications = notifications;
		this.watchlists = watchlists;
		this.filings = filings;
		this.comparisons = comparisons;
		this.stream = stream;
		this.jobs = jobs;
		this.properties = properties;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void on(FilingsDiscovered event) {
		List<Watcher> watchers = watchlists.watchers(event.companyId());
		if (watchers.isEmpty()) {
			return;
		}
		List<Long> ids = event.filingIds();
		for (long filingId : ids.subList(Math.max(0, ids.size() - properties.maxFilingsPerDiscovery()), ids.size())) {
			filings.findById(filingId).ifPresent(filing -> onNewFiling(filing, watchers));
		}
	}

	private void onNewFiling(FilingRef filing, List<Watcher> watchers) {
		List<Watcher> following = watchers.stream().filter(w -> w.follows(filing.baseForm())).toList();
		if (following.isEmpty()) {
			return;
		}
		String title = "%s filed a %s".formatted(filing.companyName(), filing.formType());
		String body = "%s filed a %s%s on %s.".formatted(filing.companyName(), filing.formType(),
				filing.reportDate() == null ? "" : " for the period ended " + DATE.format(filing.reportDate()),
				DATE.format(filing.filingDate()));
		for (Watcher watcher : following) {
			if (watcher.notificationsEnabled()) {
				notify(new NewNotification(watcher.userId(), Type.NEW_FILING, filing.companyId(), filing.id(), null,
						title, body, "/filings/" + filing.accessionNumber()));
			}
		}
		prepare(filing);
	}

	/**
	 * Downloads and processes the filing, and compares a new original 10-K or 10-Q
	 * with the previous period. Failures here never block notifications.
	 */
	private void prepare(FilingRef filing) {
		try {
			filings.requestDocuments(filing.id());
			if (properties.autoCompare() && PERIODIC_FORMS.contains(filing.formType())) {
				comparisons.compareWithPrevious(filing.id())
					.ifPresent(id -> notifications.recordMonitoredComparison(id, filing.id(), clock.instant()));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Could not prepare new filing {}: {}", filing.accessionNumber(), ex.getMessage());
		}
	}

	@ApplicationModuleListener
	void on(ComparisonCompleted event) {
		if (notifications.monitoredFiling(event.comparisonId()).isEmpty()) {
			return;
		}
		FilingRef target = filings.findById(event.targetFilingId()).orElse(null);
		FilingRef base = filings.findById(event.baseFilingId()).orElse(null);
		if (target == null || base == null) {
			return;
		}
		String title = "What changed in %s's %s".formatted(target.companyName(), target.formType());
		String body = "The new %s%s has been compared with the %s%s. See the added, removed and changed text."
			.formatted(target.formType(), period(target), base.formType(), period(base));
		for (Watcher watcher : watchlists.watchers(target.companyId())) {
			if (watcher.notificationsEnabled() && watcher.follows(target.baseForm())) {
				notify(new NewNotification(watcher.userId(), Type.COMPARISON_READY, target.companyId(), target.id(),
						event.comparisonId(), title, body, "/compare/" + event.comparisonId()));
			}
		}
	}

	private void notify(NewNotification notification) {
		notifications.insert(notification, clock.instant()).ifPresent(view -> {
			UUID userId = notification.userId();
			if (notifications.emailEnabled(userId)) {
				jobs.enqueue(NotificationEmailJob.TYPE, Map.of("notificationId", view.id()));
			}
			afterCommit(() -> stream.publish(userId, view, notifications.unreadCount(userId)));
		});
	}

	// --- User-facing operations ----------------------------------------------------

	NotificationPage list(UUID userId, boolean unreadOnly, int limit) {
		return new NotificationPage(notifications.unreadCount(userId), notifications.list(userId, unreadOnly, limit));
	}

	void markRead(UUID userId, long id) {
		if (!notifications.markRead(userId, id, clock.instant())) {
			throw new NotFoundException("Notification", id);
		}
		stream.publishUnread(userId, notifications.unreadCount(userId));
	}

	void markAllRead(UUID userId) {
		notifications.markAllRead(userId, clock.instant());
		stream.publishUnread(userId, 0);
	}

	SseEmitter stream(UUID userId) {
		return stream.open(userId, notifications.unreadCount(userId));
	}

	Preferences preferences(UUID userId) {
		return new Preferences(notifications.emailEnabled(userId));
	}

	Preferences updatePreferences(UUID userId, boolean emailEnabled) {
		notifications.setEmailEnabled(userId, emailEnabled, clock.instant());
		return new Preferences(emailEnabled);
	}

	private static String period(FilingRef filing) {
		return filing.reportDate() == null ? "" : " for the period ended " + DATE.format(filing.reportDate());
	}

	private static void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		}
		else {
			action.run();
		}
	}

}
