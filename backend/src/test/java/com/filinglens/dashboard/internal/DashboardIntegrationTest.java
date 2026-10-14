package com.filinglens.dashboard.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;

@IntegrationTest
class DashboardIntegrationTest {

	static final LocalDate SINCE = LocalDate.of(2025, 1, 1);

	@Autowired
	DashboardRepository dashboard;

	@Autowired
	JdbcClient jdbc;

	UUID alice;

	UUID bob;

	long apple;

	long microsoft;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM app_user").update();
		alice = user("alice@example.com");
		bob = user("bob@example.com");
		apple = company("0000320193", "Apple Inc.");
		microsoft = company("0000789019", "Microsoft Corp");
		jdbc.sql("""
				INSERT INTO company_ticker (ticker, company_id, rank, synced_at) VALUES ('AAPL', :c, 0, now())
				""").param("c", apple).update();
		long tenK = filing(apple, "0000320193-25-000079", "10-K", "2025-10-31", "2025-09-27");
		long priorTenK = filing(apple, "0000320193-24-000123", "10-K", "2024-11-01", "2024-09-28");
		filing(apple, "0000320193-25-000080", "8-K", "2025-11-05", null);
		filing(microsoft, "0000789019-25-000010", "10-Q", "2025-10-29", "2025-09-30");
		// Alice follows only Apple's annual reports; Bob watches Microsoft.
		watch(alice, apple, "10-K");
		watch(bob, microsoft, "10-Q");
		jdbc.sql("""
				INSERT INTO filing_comparison (base_filing_id, target_filing_id, algorithm_version, status, stats,
				                               created_at, updated_at, computed_at)
				VALUES (:b, :t, 1, 'READY', '{"added":5,"removed":2,"modified":9,"moved":1}', now(), now(), now())
				""").param("b", priorTenK).param("t", tenK).update();
		jdbc.sql("""
				INSERT INTO notification (user_id, type, company_id, filing_id, title, body, link, created_at)
				VALUES (:u, 'NEW_FILING', :c, :f, 'Apple Inc. filed a 10-K', 'b', '/filings/x', now())
				""").param("u", alice).param("c", apple).param("f", tenK).update();
	}

	@Test
	void showsOnlyTheUsersCompaniesAndFollowedForms() {
		assertThat(dashboard.companies(alice)).singleElement().satisfies(c -> {
			assertThat(c.ticker()).isEqualTo("AAPL");
			assertThat(c.latestFiling().accessionNumber()).isEqualTo("0000320193-25-000079");
			assertThat(c.unreadNotifications()).isEqualTo(1);
		});
		assertThat(dashboard.recentFilings(alice, SINCE, 10)).extracting(f -> f.formType()).containsExactly("10-K");
		assertThat(dashboard.filingsSince(alice, SINCE)).isEqualTo(1);
		assertThat(dashboard.recentFilings(bob, SINCE, 10)).extracting(f -> f.companyName())
			.containsExactly("Microsoft Corp");
	}

	@Test
	void showsChangesForWatchedCompaniesOnly() {
		assertThat(dashboard.recentChanges(alice, 5)).singleElement().satisfies(c -> {
			assertThat(c.targetPeriod()).isEqualTo(LocalDate.of(2025, 9, 27));
			assertThat(c.basePeriod()).isEqualTo(LocalDate.of(2024, 9, 28));
			assertThat(c.modified()).isEqualTo(9);
		});
		assertThat(dashboard.recentChanges(bob, 5)).isEmpty();
		assertThat(dashboard.unreadNotifications(alice)).isEqualTo(1);
		assertThat(dashboard.notifications(bob, 5)).isEmpty();
		assertThat(dashboard.conversations(alice, 5)).isEmpty();
		assertThat(dashboard.sessions(alice, 5)).isEmpty();
		assertThat(dashboard.findings(alice)).isZero();
	}

	private long company(String cik, String name) {
		return jdbc.sql("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES (:cik, :name, now(), now()) RETURNING id
				""").param("cik", cik).param("name", name).query(Long.class).single();
	}

	private long filing(long companyId, String accession, String form, String filed, String period) {
		return jdbc.sql("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, report_date, created_at, updated_at)
				VALUES (:c, :a, :form, CAST(:filed AS date), CAST(:period AS date), now(), now()) RETURNING id
				""")
			.param("c", companyId)
			.param("a", accession)
			.param("form", form)
			.param("filed", filed)
			.param("period", period)
			.query(Long.class)
			.single();
	}

	private void watch(UUID user, long companyId, String form) {
		jdbc.sql("""
				INSERT INTO watchlist_item (user_id, company_id, form_types, notify, created_at, updated_at)
				VALUES (:u, :c, ARRAY[CAST(:form AS text)], true, now(), now())
				""").param("u", user).param("c", companyId).param("form", form).update();
	}

	private UUID user(String email) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, email, password_hash, display_name, status, password_changed_at, created_at, updated_at)
				VALUES (:id, :email, 'x', 'Test', 'ACTIVE', now(), now(), now())
				""").param("id", id).param("email", email).update();
		return id;
	}

}
