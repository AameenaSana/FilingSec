package com.filinglens.monitoring.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.monitoring.internal.NotificationViews.NotificationView;
import com.filinglens.monitoring.internal.NotificationViews.Type;

/** Notifications, preferences and polling state. User-owned rows are scoped by user. */
@Repository
class NotificationRepository {

	private final JdbcClient jdbc;

	NotificationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// --- Notifications -----------------------------------------------------------

	record NewNotification(UUID userId, Type type, long companyId, long filingId, Long comparisonId, String title,
			String body, String link) {
	}

	/** @return the new notification, or empty if the user already has one of this kind for the filing */
	Optional<NotificationView> insert(NewNotification n, Instant now) {
		return jdbc.sql("""
				INSERT INTO notification (user_id, type, company_id, filing_id, comparison_id, title, body, link, created_at)
				VALUES (:user, :type, :company, :filing, :comparison, :title, :body, :link, :now)
				ON CONFLICT (user_id, type, filing_id) DO NOTHING
				RETURNING id, type, title, body, link, created_at, read_at
				""")
			.param("user", n.userId())
			.param("type", n.type().name())
			.param("company", n.companyId())
			.param("filing", n.filingId())
			.param("comparison", n.comparisonId())
			.param("title", truncate(n.title(), 300))
			.param("body", truncate(n.body(), 1000))
			.param("link", n.link())
			.param("now", Timestamp.from(now))
			.query((rs, i) -> view(rs))
			.optional();
	}

	List<NotificationView> list(UUID userId, boolean unreadOnly, int limit) {
		return jdbc.sql("""
				SELECT id, type, title, body, link, created_at, read_at FROM notification
				WHERE user_id = :user AND (NOT :unread OR read_at IS NULL)
				ORDER BY created_at DESC, id DESC LIMIT :limit
				""")
			.param("user", userId)
			.param("unread", unreadOnly)
			.param("limit", limit)
			.query((rs, i) -> view(rs))
			.list();
	}

	int unreadCount(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM notification WHERE user_id = :user AND read_at IS NULL")
			.param("user", userId)
			.query(Integer.class)
			.single();
	}

	/** @return false if the notification does not exist or belongs to someone else */
	boolean markRead(UUID userId, long id, Instant now) {
		return jdbc.sql("""
				UPDATE notification SET read_at = COALESCE(read_at, :now) WHERE id = :id AND user_id = :user
				""").param("now", Timestamp.from(now)).param("id", id).param("user", userId).update() == 1;
	}

	void markAllRead(UUID userId, Instant now) {
		jdbc.sql("UPDATE notification SET read_at = :now WHERE user_id = :user AND read_at IS NULL")
			.param("now", Timestamp.from(now))
			.param("user", userId)
			.update();
	}

	record EmailContent(UUID userId, String title, String body, String link, String companyName) {
	}

	Optional<EmailContent> emailContent(long id) {
		return jdbc.sql("""
				SELECT n.user_id, n.title, n.body, n.link, c.name FROM notification n
				JOIN company c ON c.id = n.company_id WHERE n.id = :id
				""")
			.param("id", id)
			.query((rs, i) -> new EmailContent(rs.getObject("user_id", UUID.class), rs.getString("title"),
					rs.getString("body"), rs.getString("link"), rs.getString("name")))
			.optional();
	}

	// --- Preferences -------------------------------------------------------------

	/** Email is on unless the user turned it off. */
	boolean emailEnabled(UUID userId) {
		return jdbc.sql("SELECT email_enabled FROM notification_preference WHERE user_id = :user")
			.param("user", userId)
			.query(Boolean.class)
			.optional()
			.orElse(true);
	}

	void setEmailEnabled(UUID userId, boolean enabled, Instant now) {
		jdbc.sql("""
				INSERT INTO notification_preference (user_id, email_enabled, updated_at) VALUES (:user, :enabled, :now)
				ON CONFLICT (user_id) DO UPDATE SET email_enabled = :enabled, updated_at = :now
				""").param("user", userId).param("enabled", enabled).param("now", Timestamp.from(now)).update();
	}

	// --- Polling and automatic comparisons ------------------------------------------

	/**
	 * Claims a company for polling if it has not been polled since {@code cutoff}.
	 * Atomic, so concurrent schedulers never poll the same company twice.
	 */
	boolean claimPoll(long companyId, Instant cutoff, Instant now) {
		return jdbc.sql("""
				INSERT INTO company_poll_state (company_id, last_polled_at) VALUES (:company, :now)
				ON CONFLICT (company_id) DO UPDATE SET last_polled_at = :now
				WHERE company_poll_state.last_polled_at <= :cutoff
				RETURNING company_id
				""")
			.param("company", companyId)
			.param("now", Timestamp.from(now))
			.param("cutoff", Timestamp.from(cutoff))
			.query(Long.class)
			.optional()
			.isPresent();
	}

	void recordPollResult(long companyId, String error, Instant now) {
		jdbc.sql("""
				UPDATE company_poll_state
				SET last_success_at = CASE WHEN CAST(:error AS varchar) IS NULL THEN :now ELSE last_success_at END,
				    last_error = :error
				WHERE company_id = :company
				""")
			.param("error", error == null ? null : truncate(error, 500))
			.param("now", Timestamp.from(now))
			.param("company", companyId)
			.update();
	}

	void recordMonitoredComparison(long comparisonId, long filingId, Instant now) {
		jdbc.sql("""
				INSERT INTO monitored_comparison (comparison_id, filing_id, created_at) VALUES (:c, :f, :now)
				ON CONFLICT DO NOTHING
				""").param("c", comparisonId).param("f", filingId).param("now", Timestamp.from(now)).update();
	}

	/** @return the filing a comparison was started for automatically */
	Optional<Long> monitoredFiling(long comparisonId) {
		return jdbc.sql("SELECT filing_id FROM monitored_comparison WHERE comparison_id = :c")
			.param("c", comparisonId)
			.query(Long.class)
			.optional();
	}

	private static NotificationView view(ResultSet rs) throws SQLException {
		return new NotificationView(rs.getLong("id"), Type.valueOf(rs.getString("type")), rs.getString("title"),
				rs.getString("body"), rs.getString("link"), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("read_at") != null);
	}

	private static String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}

}
