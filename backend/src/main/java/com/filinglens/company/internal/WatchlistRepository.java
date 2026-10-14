package com.filinglens.company.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.company.internal.CompanyViews.CompanySummary;
import com.filinglens.company.internal.CompanyViews.WatchlistEntry;

/** Every query is scoped to the owning user. */
@Repository
class WatchlistRepository {

	private static final String SELECT_ENTRY = """
			SELECT w.id, w.form_types, w.notify, w.created_at, c.cik, c.name,
			       ARRAY(SELECT t.ticker FROM company_ticker t WHERE t.company_id = c.id ORDER BY t.rank) AS tickers,
			       (SELECT t.exchange FROM company_ticker t WHERE t.company_id = c.id ORDER BY t.rank LIMIT 1) AS exchange
			FROM watchlist_item w
			JOIN company c ON c.id = w.company_id
			""";

	private final JdbcClient jdbc;

	WatchlistRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<WatchlistEntry> findAll(UUID userId) {
		return jdbc.sql(SELECT_ENTRY + "WHERE w.user_id = :userId ORDER BY lower(c.name)")
			.param("userId", userId)
			.query(WatchlistRepository::entry)
			.list();
	}

	Optional<WatchlistEntry> find(UUID userId, long id) {
		return jdbc.sql(SELECT_ENTRY + "WHERE w.user_id = :userId AND w.id = :id")
			.param("userId", userId)
			.param("id", id)
			.query(WatchlistRepository::entry)
			.optional();
	}

	int count(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM watchlist_item WHERE user_id = :userId")
			.param("userId", userId)
			.query(Integer.class)
			.single();
	}

	/** @return the new id, or empty if the user already watches the company */
	Optional<Long> insert(UUID userId, long companyId, List<String> formTypes, boolean notify, Instant now) {
		return jdbc.sql("""
				INSERT INTO watchlist_item (user_id, company_id, form_types, notify, created_at, updated_at)
				VALUES (:userId, :companyId, CAST(:formTypes AS text[]), :notify, :now, :now)
				ON CONFLICT (user_id, company_id) DO NOTHING
				RETURNING id
				""")
			.param("userId", userId)
			.param("companyId", companyId)
			.param("formTypes", formTypes.toArray(String[]::new))
			.param("notify", notify)
			.param("now", Timestamp.from(now))
			.query(Long.class)
			.optional();
	}

	boolean update(UUID userId, long id, List<String> formTypes, boolean notify, Instant now) {
		return jdbc.sql("""
				UPDATE watchlist_item SET form_types = CAST(:formTypes AS text[]), notify = :notify, updated_at = :now
				WHERE id = :id AND user_id = :userId
				""")
			.param("formTypes", formTypes.toArray(String[]::new))
			.param("notify", notify)
			.param("now", Timestamp.from(now))
			.param("id", id)
			.param("userId", userId)
			.update() == 1;
	}

	boolean delete(UUID userId, long id) {
		return jdbc.sql("DELETE FROM watchlist_item WHERE id = :id AND user_id = :userId")
			.param("id", id)
			.param("userId", userId)
			.update() == 1;
	}

	private static WatchlistEntry entry(ResultSet rs, int rowNum) throws SQLException {
		return new WatchlistEntry(rs.getLong("id"), CompanyRepository.summary(rs, rowNum),
				CompanyRepository.strings(rs.getArray("form_types")), rs.getBoolean("notify"),
				CompanyRepository.instant(rs, "created_at"));
	}

}
