package com.filinglens.company.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.company.internal.CompanyViews.CompanyDetail;
import com.filinglens.company.internal.CompanyViews.CompanySummary;
import com.filinglens.edgar.Cik;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.CompanyTickerEntry;

@Repository
class CompanyRepository {

	private static final Pattern TICKER_LIKE = Pattern.compile("[A-Za-z0-9.\\-]{1,10}");

	private static final String TICKERS = """
			ARRAY(SELECT t.ticker FROM company_ticker t WHERE t.company_id = c.id ORDER BY t.rank) AS tickers,
			(SELECT t.exchange FROM company_ticker t WHERE t.company_id = c.id ORDER BY t.rank LIMIT 1) AS exchange
			""";

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	CompanyRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
	}

	long count() {
		return jdbc.sql("SELECT count(*) FROM company").query(Long.class).single();
	}

	/**
	 * Ranks exact CIK and ticker matches first, then name prefix, ticker prefix, name
	 * substring and finally fuzzy (trigram) name matches.
	 */
	List<CompanySummary> search(String query, int limit) {
		String q = query.strip();
		String cik = Cik.isValid(q) ? Cik.normalize(q) : "";
		String ticker = TICKER_LIKE.matcher(q).matches() ? q.toUpperCase() : "";
		String escaped = escapeLike(q.toLowerCase());
		return jdbc.sql("""
				SELECT c.cik, c.name, {TICKERS}
				FROM company c
				CROSS JOIN LATERAL (SELECT CASE
				    WHEN c.cik = :cik THEN 100
				    WHEN EXISTS (SELECT 1 FROM company_ticker t WHERE t.company_id = c.id AND t.ticker = :ticker) THEN 90
				    WHEN lower(c.name) LIKE :prefix ESCAPE '\\' THEN 70
				    WHEN EXISTS (SELECT 1 FROM company_ticker t
				                 WHERE t.company_id = c.id AND t.ticker LIKE :tickerPrefix ESCAPE '\\') THEN 60
				    WHEN lower(c.name) LIKE :contains ESCAPE '\\' THEN 50
				    ELSE 0 END AS score) s
				WHERE c.cik = :cik
				   OR c.id IN (SELECT t.company_id FROM company_ticker t WHERE t.ticker LIKE :tickerPrefix ESCAPE '\\')
				   OR lower(c.name) LIKE :contains ESCAPE '\\'
				   OR c.name % :q
				ORDER BY s.score DESC, similarity(c.name, :q) DESC, c.name
				LIMIT :limit
				""".replace("{TICKERS}", TICKERS))
			.param("cik", cik)
			.param("ticker", ticker)
			.param("tickerPrefix", ticker.isEmpty() ? "" : escapeLike(ticker) + "%")
			.param("prefix", escaped + "%")
			.param("contains", "%" + escaped + "%")
			.param("q", q)
			.param("limit", limit)
			.query(CompanyRepository::summary)
			.list();
	}

	Optional<CompanyRow> findByCik(String cik) {
		return jdbc.sql("SELECT id, profile_synced_at FROM company WHERE cik = :cik")
			.param("cik", cik)
			.query((rs, n) -> new CompanyRow(rs.getLong("id"), instant(rs, "profile_synced_at")))
			.optional();
	}

	Optional<CompanyDetail> findDetail(String cik, UUID userId) {
		return jdbc.sql("""
				SELECT c.cik, c.name, c.entity_type, c.sic, c.sic_description, c.category, c.fiscal_year_end,
				       c.state_of_incorporation, c.profile_synced_at, {TICKERS},
				       (SELECT w.id FROM watchlist_item w WHERE w.company_id = c.id AND w.user_id = :userId) AS watch_id
				FROM company c
				WHERE c.cik = :cik
				""".replace("{TICKERS}", TICKERS)).param("cik", cik).param("userId", userId).query((rs, n) -> {
			Long watchId = rs.getObject("watch_id", Long.class);
			return new CompanyDetail(rs.getString("cik"), rs.getString("name"), strings(rs.getArray("tickers")),
					rs.getString("exchange"), rs.getString("entity_type"), rs.getString("sic"),
					rs.getString("sic_description"), rs.getString("category"), rs.getString("fiscal_year_end"),
					rs.getString("state_of_incorporation"), CompanyViews.edgarUrl(rs.getString("cik")),
					instant(rs, "profile_synced_at"), watchId);
		}).optional();
	}

	/** Inserts or updates a company from its EDGAR submissions and returns its id. */
	long upsertProfile(CompanySubmissions profile, Instant now) {
		long id = jdbc.sql("""
				INSERT INTO company (cik, name, entity_type, sic, sic_description, category, fiscal_year_end,
				                     state_of_incorporation, profile_synced_at, created_at, updated_at)
				VALUES (:cik, :name, :entityType, :sic, :sicDescription, :category, :fiscalYearEnd,
				        :stateOfIncorporation, :now, :now, :now)
				ON CONFLICT (cik) DO UPDATE SET
				    name = EXCLUDED.name, entity_type = EXCLUDED.entity_type, sic = EXCLUDED.sic,
				    sic_description = EXCLUDED.sic_description, category = EXCLUDED.category,
				    fiscal_year_end = EXCLUDED.fiscal_year_end,
				    state_of_incorporation = EXCLUDED.state_of_incorporation,
				    profile_synced_at = EXCLUDED.profile_synced_at, updated_at = EXCLUDED.updated_at
				RETURNING id
				""")
			.param("cik", profile.cik())
			.param("name", truncate(profile.name(), 300))
			.param("entityType", truncate(profile.entityType(), 50))
			.param("sic", truncate(profile.sic(), 10))
			.param("sicDescription", truncate(profile.sicDescription(), 200))
			.param("category", truncate(profile.category(), 100))
			.param("fiscalYearEnd", profile.fiscalYearEnd() != null && profile.fiscalYearEnd().matches("\\d{4}")
					? profile.fiscalYearEnd() : null)
			.param("stateOfIncorporation", truncate(profile.stateOfIncorporation(), 10))
			.param("now", Timestamp.from(now))
			.query(Long.class)
			.single();
		// The directory is authoritative for tickers; only fill in ones it does not know.
		List<String> tickers = profile.tickers();
		for (int i = 0; i < tickers.size(); i++) {
			jdbc.sql("""
					INSERT INTO company_ticker (ticker, company_id, exchange, rank, synced_at)
					VALUES (:ticker, :companyId, :exchange, :rank, :now)
					ON CONFLICT (ticker) DO NOTHING
					""")
				.param("ticker", truncate(tickers.get(i).strip().toUpperCase(), 20))
				.param("companyId", id)
				.param("exchange", i < profile.exchanges().size() ? truncate(profile.exchanges().get(i), 50) : null)
				.param("rank", 1_000_000 + i)
				.param("now", Timestamp.from(now))
				.update();
		}
		return id;
	}

	/**
	 * Applies SEC's ticker directory. Names are only taken from the directory for
	 * companies whose profile has never been loaded (the submissions API is the
	 * better source). Tickers absent from this sync are removed unless the
	 * directory looks truncated.
	 * @return number of tickers applied
	 */
	int applyDirectory(List<CompanyTickerEntry> entries, Instant now, boolean removeStaleTickers) {
		Timestamp syncedAt = Timestamp.from(now);
		Map<String, String> names = new LinkedHashMap<>();
		entries.forEach(e -> names.putIfAbsent(e.cik(), e.name()));

		List<Object[]> companyRows = new ArrayList<>();
		names.forEach((cik, name) -> companyRows.add(new Object[] { cik, truncate(name, 300), syncedAt, syncedAt }));
		jdbcTemplate.batchUpdate("""
				INSERT INTO company (cik, name, created_at, updated_at) VALUES (?, ?, ?, ?)
				ON CONFLICT (cik) DO UPDATE SET name = EXCLUDED.name, updated_at = EXCLUDED.updated_at
				WHERE company.profile_synced_at IS NULL AND company.name IS DISTINCT FROM EXCLUDED.name
				""", companyRows);

		Map<String, Long> ids = new HashMap<>();
		jdbcTemplate.query("SELECT cik, id FROM company WHERE cik = ANY (?)",
				ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", names.keySet().toArray())),
				rs -> {
					ids.put(rs.getString("cik"), rs.getLong("id"));
				});

		List<Object[]> tickerRows = new ArrayList<>();
		for (int rank = 0; rank < entries.size(); rank++) {
			CompanyTickerEntry entry = entries.get(rank);
			tickerRows.add(new Object[] { truncate(entry.ticker(), 20), ids.get(entry.cik()),
					truncate(entry.exchange(), 50), rank, syncedAt });
		}
		jdbcTemplate.batchUpdate("""
				INSERT INTO company_ticker (ticker, company_id, exchange, rank, synced_at) VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (ticker) DO UPDATE SET company_id = EXCLUDED.company_id, exchange = EXCLUDED.exchange,
				    rank = EXCLUDED.rank, synced_at = EXCLUDED.synced_at
				""", tickerRows);
		if (removeStaleTickers) {
			jdbc.sql("DELETE FROM company_ticker WHERE synced_at < :syncedAt").param("syncedAt", syncedAt).update();
		}
		return tickerRows.size();
	}

	record CompanyRow(long id, Instant profileSyncedAt) {
	}

	static CompanySummary summary(ResultSet rs, int rowNum) throws SQLException {
		return new CompanySummary(rs.getString("cik"), rs.getString("name"), strings(rs.getArray("tickers")),
				rs.getString("exchange"));
	}

	static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
	}

	static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
