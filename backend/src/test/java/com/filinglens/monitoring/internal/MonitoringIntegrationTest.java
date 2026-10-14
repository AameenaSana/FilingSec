package com.filinglens.monitoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.company.Watchlists;
import com.filinglens.identity.UserDirectory;
import com.filinglens.monitoring.internal.NotificationRepository.NewNotification;
import com.filinglens.monitoring.internal.NotificationViews.Type;

@IntegrationTest
class MonitoringIntegrationTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Autowired
	NotificationRepository notifications;

	@Autowired
	Watchlists watchlists;

	@Autowired
	UserDirectory users;

	@Autowired
	JdbcClient jdbc;

	long companyId;

	long filingId;

	UUID alice;

	UUID bob;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM app_user").update();
		companyId = jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES ('0000320193', 'Apple Inc.', now(), now())
				RETURNING id
				""").query(Long.class).single();
		filingId = jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, report_date, created_at, updated_at)
				VALUES (:c, '0000320193-25-000079', '10-K', DATE '2025-10-31', DATE '2025-09-27', now(), now()) RETURNING id
				""").param("c", companyId).query(Long.class).single();
		alice = user("alice@example.com", true);
		bob = user("bob@example.com", false);
	}

	@Test
	void notificationsAreDeduplicatedPrivateAndMarkedRead() {
		var first = notifications.insert(notification(alice), NOW);
		assertThat(first).isPresent();
		assertThat(notifications.insert(notification(alice), NOW)).as("same filing again").isEmpty();
		notifications.insert(notification(bob), NOW);

		assertThat(notifications.unreadCount(alice)).isEqualTo(1);
		assertThat(notifications.list(alice, false, 10)).singleElement()
			.satisfies(n -> assertThat(n.title()).isEqualTo("Apple Inc. filed a 10-K"));
		assertThat(notifications.markRead(bob, first.get().id(), NOW)).isFalse();
		assertThat(notifications.markRead(alice, first.get().id(), NOW)).isTrue();
		assertThat(notifications.unreadCount(alice)).isZero();
		assertThat(notifications.list(alice, true, 10)).isEmpty();
		assertThat(notifications.unreadCount(bob)).isEqualTo(1);
		notifications.markAllRead(bob, NOW);
		assertThat(notifications.unreadCount(bob)).isZero();
		assertThat(notifications.emailContent(first.get().id()).orElseThrow().companyName()).isEqualTo("Apple Inc.");
	}

	@Test
	void emailIsOnUntilTurnedOff() {
		assertThat(notifications.emailEnabled(alice)).isTrue();
		notifications.setEmailEnabled(alice, false, NOW);
		assertThat(notifications.emailEnabled(alice)).isFalse();
		notifications.setEmailEnabled(alice, true, NOW);
		assertThat(notifications.emailEnabled(alice)).isTrue();
	}

	@Test
	void eachCompanyIsPolledOncePerInterval() {
		Instant cutoff = NOW.minus(Duration.ofMinutes(15));
		assertThat(notifications.claimPoll(companyId, cutoff, NOW)).isTrue();
		assertThat(notifications.claimPoll(companyId, cutoff, NOW.plusSeconds(60))).isFalse();
		assertThat(notifications.claimPoll(companyId, NOW.plusSeconds(1), NOW.plus(Duration.ofMinutes(16)))).isTrue();
		notifications.recordPollResult(companyId, "SEC unavailable", NOW);
		assertThat(jdbc.sql("SELECT last_error FROM company_poll_state").query(String.class).single())
			.isEqualTo("SEC unavailable");
	}

	@Test
	void watchersAndContactsAreReadFromOtherModules() {
		jdbc.sql("""
				INSERT INTO watchlist_item (user_id, company_id, form_types, notify, created_at, updated_at)
				VALUES (:u, :c, ARRAY['10-K'], false, now(), now())
				""").param("u", alice).param("c", companyId).update();

		assertThat(watchlists.watchedCompanies()).extracting(c -> c.cik()).containsExactly("0000320193");
		assertThat(watchlists.watchers(companyId)).singleElement().satisfies(w -> {
			assertThat(w.follows("10-K")).isTrue();
			assertThat(w.follows("8-K")).isFalse();
			assertThat(w.notificationsEnabled()).isFalse();
		});
		assertThat(users.contact(alice).orElseThrow().reachable()).isTrue();
		assertThat(users.contact(bob).orElseThrow().reachable()).isFalse();
		assertThat(users.contact(UUID.randomUUID())).isEmpty();
	}

	private NewNotification notification(UUID user) {
		return new NewNotification(user, Type.NEW_FILING, companyId, filingId, null, "Apple Inc. filed a 10-K",
				"Apple Inc. filed a 10-K on Oct 31, 2025.", "/filings/0000320193-25-000079");
	}

	private UUID user(String email, boolean verified) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, email, password_hash, display_name, status, email_verified_at,
				                      password_changed_at, created_at, updated_at)
				VALUES (:id, :email, 'x', 'Test', :status, :verified, now(), now(), now())
				""")
			.param("id", id)
			.param("email", email)
			.param("status", verified ? "ACTIVE" : "PENDING_VERIFICATION")
			.param("verified", verified ? Timestamp.from(NOW) : null)
			.update();
		return id;
	}

}
