package com.filinglens.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.filinglens.IntegrationTest;
import com.filinglens.company.internal.CompanyViews.CompanySummary;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.CompanyTickerEntry;
import com.filinglens.platform.error.NotFoundException;

@IntegrationTest
class CompanyIntegrationTest {

	static final Instant T1 = Instant.parse("2026-06-01T06:00:00Z");

	@Autowired
	CompanyRepository companies;

	@Autowired
	WatchlistRepository watchlistRepository;

	@Autowired
	WatchlistService watchlist;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("DELETE FROM company").update();
		jdbc.sql("DELETE FROM app_user").update();
	}

	@Test
	void directorySyncUpsertsCompaniesAndTickersAndRemovesStaleTickers() {
		companies.applyDirectory(directory("OLDT"), T1, true);
		companies.applyDirectory(directory(null), T1.plusSeconds(86_400), true);

		assertThat(companies.count()).isEqualTo(4);
		assertThat(companies.search("GOOG", 5)).first()
			.satisfies(c -> assertThat(c.tickers()).containsExactly("GOOGL", "GOOG"));
		assertThat(jdbc.sql("SELECT count(*) FROM company_ticker WHERE ticker = 'OLDT'").query(Long.class).single())
			.isZero();
	}

	@Test
	void searchRanksExactTickerAndCikBeforeFuzzyNameMatches() {
		companies.applyDirectory(directory(null), T1, true);

		assertThat(companies.search("msft", 5)).extracting(CompanySummary::name).first().isEqualTo("MICROSOFT CORP");
		assertThat(companies.search("320193", 5)).extracting(CompanySummary::name).first().isEqualTo("Apple Inc.");
		assertThat(companies.search("micro", 5)).extracting(CompanySummary::name).contains("MICROSOFT CORP");
		assertThat(companies.search("100%_", 5)).isEmpty();
	}

	@Test
	void profileFromSubmissionsReplacesDirectoryNameAndSurvivesNextSync() {
		companies.applyDirectory(directory(null), T1, true);
		companies.upsertProfile(new CompanySubmissions("0000789019", "Microsoft Corporation", "operating", "7372",
				"Services-Prepackaged Software", null, "0630", "WA", List.of("MSFT"), List.of("Nasdaq"), List.of(), List.of()), T1);
		companies.applyDirectory(directory(null), T1.plusSeconds(60), true);

		var detail = companies.findDetail("0000789019", UUID.randomUUID()).orElseThrow();
		assertThat(detail.name()).isEqualTo("Microsoft Corporation");
		assertThat(detail.fiscalYearEnd()).isEqualTo("0630");
		assertThat(detail.profileSyncedAt()).isEqualTo(T1);
	}

	@Test
	void watchlistIsIsolatedPerUser() {
		companies.applyDirectory(directory(null), T1, true);
		long apple = companies.findByCik("0000320193").orElseThrow().id();
		UUID alice = user("alice@example.com");
		UUID bob = user("bob@example.com");

		long aliceItem = watchlistRepository.insert(alice, apple, List.of("10-K"), true, T1).orElseThrow();

		assertThat(watchlist.list(alice)).hasSize(1);
		assertThat(watchlist.list(bob)).isEmpty();
		assertThatThrownBy(() -> watchlist.remove(bob, aliceItem)).isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> watchlist.update(bob, aliceItem, List.of("8-K"), false))
			.isInstanceOf(NotFoundException.class);
		assertThat(watchlist.update(alice, aliceItem, List.of("8-K", "10-Q"), false).formTypes())
			.containsExactly("10-Q", "8-K");
		assertThat(companies.findDetail("0000320193", alice).orElseThrow().watchlistItemId()).isEqualTo(aliceItem);
		assertThat(companies.findDetail("0000320193", bob).orElseThrow().watchlistItemId()).isNull();

		// Deleting an account removes its watchlist.
		jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", alice).update();
		assertThat(watchlist.list(alice)).isEmpty();
	}

	private static List<CompanyTickerEntry> directory(String extraTicker) {
		List<CompanyTickerEntry> entries = new ArrayList<>(List.of(
				new CompanyTickerEntry("0000320193", "Apple Inc.", "AAPL", "Nasdaq"),
				new CompanyTickerEntry("0000789019", "MICROSOFT CORP", "MSFT", "Nasdaq"),
				new CompanyTickerEntry("0001652044", "Alphabet Inc.", "GOOGL", "Nasdaq"),
				new CompanyTickerEntry("0001652044", "Alphabet Inc.", "GOOG", "Nasdaq"),
				new CompanyTickerEntry("0001067983", "BERKSHIRE HATHAWAY INC", "BRK-B", "NYSE")));
		if (extraTicker != null) {
			entries.add(new CompanyTickerEntry("0001067983", "BERKSHIRE HATHAWAY INC", extraTicker, "NYSE"));
		}
		return entries;
	}

	private UUID user(String email) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, email, password_hash, display_name, status, password_changed_at,
				                      created_at, updated_at)
				VALUES (:id, :email, 'x', 'Test', 'ACTIVE', now(), now(), now())
				""").param("id", id).param("email", email).update();
		return id;
	}

}
