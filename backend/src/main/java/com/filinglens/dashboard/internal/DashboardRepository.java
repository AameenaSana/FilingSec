package com.filinglens.dashboard.internal;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.dashboard.internal.DashboardViews.ConversationItem;
import com.filinglens.dashboard.internal.DashboardViews.FilingInfo;
import com.filinglens.dashboard.internal.DashboardViews.NotificationItem;
import com.filinglens.dashboard.internal.DashboardViews.RecentChange;
import com.filinglens.dashboard.internal.DashboardViews.RecentFiling;
import com.filinglens.dashboard.internal.DashboardViews.SessionItem;
import com.filinglens.dashboard.internal.DashboardViews.WatchedCompany;

/** Read-only queries, each scoped to one user. */
@Repository
class DashboardRepository {

	static final int RECENT_DAYS = 90;

	private static final String TICKER = "(SELECT t.ticker FROM company_ticker t WHERE t.company_id = c.id ORDER BY t.rank LIMIT 1)";

	private final JdbcClient jdbc;

	DashboardRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<WatchedCompany> companies(UUID userId) {
		return jdbc.sql("""
				SELECT c.cik, c.name, %s AS ticker, w.form_types,
				       lf.accession_number, lf.form_type, lf.filing_date, lf.report_date,
				       (SELECT count(*) FROM notification n
				        WHERE n.user_id = w.user_id AND n.company_id = c.id AND n.read_at IS NULL) AS unread,
				       ps.last_success_at
				FROM watchlist_item w
				JOIN company c ON c.id = w.company_id
				LEFT JOIN LATERAL (
				    SELECT f.accession_number, f.form_type, f.filing_date, f.report_date FROM filing f
				    WHERE f.company_id = c.id AND replace(f.form_type, '/A', '') = ANY (w.form_types)
				    ORDER BY f.filing_date DESC, f.id DESC LIMIT 1) lf ON TRUE
				LEFT JOIN company_poll_state ps ON ps.company_id = c.id
				WHERE w.user_id = :user
				ORDER BY lf.filing_date DESC NULLS LAST, lower(c.name)
				""".formatted(TICKER)).param("user", userId).query((rs, n) -> {
			String accession = rs.getString("accession_number");
			return new WatchedCompany(rs.getString("cik"), rs.getString("name"), rs.getString("ticker"),
					Arrays.asList((String[]) rs.getArray("form_types").getArray()),
					accession == null ? null
							: new FilingInfo(accession, rs.getString("form_type"), date(rs, "filing_date"),
									date(rs, "report_date")),
					rs.getInt("unread"), instant(rs, "last_success_at"));
		}).list();
	}

	/** Filings of watched companies, in the forms the user follows for each, since {@code since}. */
	List<RecentFiling> recentFilings(UUID userId, LocalDate since, int limit) {
		return jdbc.sql("""
				SELECT c.cik, c.name, %s AS ticker, f.accession_number, f.form_type, f.filing_date, f.report_date, f.items
				FROM watchlist_item w
				JOIN company c ON c.id = w.company_id
				JOIN filing f ON f.company_id = c.id AND replace(f.form_type, '/A', '') = ANY (w.form_types)
				WHERE w.user_id = :user AND f.filing_date >= :since
				ORDER BY f.filing_date DESC, f.acceptance_at DESC NULLS LAST, f.id DESC
				LIMIT :limit
				""".formatted(TICKER))
			.param("user", userId)
			.param("since", Date.valueOf(since))
			.param("limit", limit)
			.query((rs, n) -> new RecentFiling(rs.getString("cik"), rs.getString("name"), rs.getString("ticker"),
					rs.getString("accession_number"), rs.getString("form_type"), date(rs, "filing_date"),
					date(rs, "report_date"), rs.getString("items")))
			.list();
	}

	int filingsSince(UUID userId, LocalDate since) {
		return jdbc.sql("""
				SELECT count(*) FROM watchlist_item w
				JOIN filing f ON f.company_id = w.company_id AND replace(f.form_type, '/A', '') = ANY (w.form_types)
				WHERE w.user_id = :user AND f.filing_date >= :since
				""").param("user", userId).param("since", Date.valueOf(since)).query(Integer.class).single();
	}

	/** Completed comparisons whose later filing belongs to a watched company. */
	List<RecentChange> recentChanges(UUID userId, int limit) {
		return jdbc.sql("""
				SELECT fc.id, c.name, %s AS ticker, t.form_type,
				       COALESCE(b.report_date, b.filing_date) AS base_period,
				       COALESCE(t.report_date, t.filing_date) AS target_period,
				       COALESCE((fc.stats ->> 'added')::int, 0) AS added,
				       COALESCE((fc.stats ->> 'removed')::int, 0) AS removed,
				       COALESCE((fc.stats ->> 'modified')::int, 0) AS modified,
				       COALESCE((fc.stats ->> 'moved')::int, 0) AS moved,
				       fc.computed_at
				FROM filing_comparison fc
				JOIN filing t ON t.id = fc.target_filing_id
				JOIN filing b ON b.id = fc.base_filing_id
				JOIN company c ON c.id = t.company_id
				WHERE fc.status = 'READY'
				  AND EXISTS (SELECT 1 FROM watchlist_item w WHERE w.user_id = :user AND w.company_id = c.id)
				ORDER BY fc.computed_at DESC, fc.id DESC
				LIMIT :limit
				""".formatted(TICKER)).param("user", userId).param("limit", limit).query((rs, n) -> new RecentChange(
						rs.getLong("id"), rs.getString("name"), rs.getString("ticker"), rs.getString("form_type"),
						date(rs, "base_period"), date(rs, "target_period"), rs.getInt("added"), rs.getInt("removed"),
						rs.getInt("modified"), rs.getInt("moved"), instant(rs, "computed_at")))
			.list();
	}

	List<NotificationItem> notifications(UUID userId, int limit) {
		return jdbc.sql("""
				SELECT id, type, title, link, created_at, read_at FROM notification
				WHERE user_id = :user ORDER BY created_at DESC, id DESC LIMIT :limit
				""")
			.param("user", userId)
			.param("limit", limit)
			.query((rs, n) -> new NotificationItem(rs.getLong("id"), rs.getString("type"), rs.getString("title"),
					rs.getString("link"), instant(rs, "created_at"), rs.getTimestamp("read_at") != null))
			.list();
	}

	int unreadNotifications(UUID userId) {
		return count("SELECT count(*) FROM notification WHERE user_id = :user AND read_at IS NULL", userId);
	}

	List<ConversationItem> conversations(UUID userId, int limit) {
		return jdbc.sql("""
				SELECT id, title, updated_at FROM conversation WHERE user_id = :user
				ORDER BY updated_at DESC, id DESC LIMIT :limit
				""")
			.param("user", userId)
			.param("limit", limit)
			.query((rs, n) -> new ConversationItem(rs.getLong("id"), rs.getString("title"), instant(rs, "updated_at")))
			.list();
	}

	List<SessionItem> sessions(UUID userId, int limit) {
		return jdbc.sql("""
				SELECT s.id, s.title, s.updated_at,
				       (SELECT count(*) FROM finding f WHERE f.research_session_id = s.id) AS findings
				FROM research_session s WHERE s.user_id = :user
				ORDER BY s.updated_at DESC, s.id DESC LIMIT :limit
				""")
			.param("user", userId)
			.param("limit", limit)
			.query((rs, n) -> new SessionItem(rs.getLong("id"), rs.getString("title"), rs.getInt("findings"),
					instant(rs, "updated_at")))
			.list();
	}

	int findings(UUID userId) {
		return count("SELECT count(*) FROM finding WHERE user_id = :user", userId);
	}

	private int count(String sql, UUID userId) {
		return jdbc.sql(sql).param("user", userId).query(Integer.class).single();
	}

	private static LocalDate date(ResultSet rs, String column) throws SQLException {
		Date date = rs.getDate(column);
		return date == null ? null : date.toLocalDate();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

}
