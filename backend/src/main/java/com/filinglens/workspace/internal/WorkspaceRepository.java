package com.filinglens.workspace.internal;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.workspace.internal.WorkspaceViews.FilingRef;
import com.filinglens.workspace.internal.WorkspaceViews.FindingView;
import com.filinglens.workspace.internal.WorkspaceViews.SessionSummary;

/** Every query is scoped to the owning user. */
@Repository
class WorkspaceRepository {

	private static final String SELECT_FINDING = """
			SELECT fd.*, c.cik FROM finding fd
			JOIN filing f ON f.id = fd.filing_id
			JOIN company c ON c.id = f.company_id
			""";

	private final JdbcClient jdbc;

	WorkspaceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// --- Sessions ----------------------------------------------------------------

	record SessionRow(long id, String title, String description, Instant createdAt, Instant updatedAt) {
	}

	long createSession(UUID userId, String title, String description, Instant now) {
		return jdbc.sql("""
				INSERT INTO research_session (user_id, title, description, created_at, updated_at)
				VALUES (:user, :title, :description, :now, :now) RETURNING id
				""")
			.param("user", userId)
			.param("title", title)
			.param("description", description)
			.param("now", Timestamp.from(now))
			.query(Long.class)
			.single();
	}

	List<SessionSummary> sessions(UUID userId) {
		return jdbc.sql("""
				SELECT s.id, s.title, s.description, s.updated_at,
				       (SELECT count(*) FROM finding f WHERE f.research_session_id = s.id) AS findings
				FROM research_session s WHERE s.user_id = :user ORDER BY s.updated_at DESC, s.id DESC
				""")
			.param("user", userId)
			.query((rs, n) -> new SessionSummary(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
					rs.getInt("findings"), rs.getTimestamp("updated_at").toInstant()))
			.list();
	}

	Optional<SessionRow> session(UUID userId, long id) {
		return jdbc.sql("""
				SELECT id, title, description, created_at, updated_at FROM research_session
				WHERE id = :id AND user_id = :user
				""")
			.param("id", id)
			.param("user", userId)
			.query((rs, n) -> new SessionRow(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
					rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
			.optional();
	}

	boolean updateSession(UUID userId, long id, String title, String description, Instant now) {
		return jdbc.sql("""
				UPDATE research_session SET title = :title, description = :description, updated_at = :now
				WHERE id = :id AND user_id = :user
				""")
			.param("title", title)
			.param("description", description)
			.param("now", Timestamp.from(now))
			.param("id", id)
			.param("user", userId)
			.update() == 1;
	}

	void touchSession(long id, Instant now) {
		jdbc.sql("UPDATE research_session SET updated_at = :now WHERE id = :id")
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update();
	}

	boolean deleteSession(UUID userId, long id) {
		return jdbc.sql("DELETE FROM research_session WHERE id = :id AND user_id = :user")
			.param("id", id)
			.param("user", userId)
			.update() == 1;
	}

	// --- Findings ----------------------------------------------------------------

	/** Source fields resolved by the server from a citation, change or passage. */
	record FindingSource(String sourceType, long filingId, String accessionNumber, String companyName,
			String formType, LocalDate reportDate, String sectionKey, String sectionTitle, List<String> blockRefs,
			int blockOrdinal, String quotedText, String previousText, String previousAccession,
			Integer previousBlockOrdinal, String changeType, Long comparisonId, Integer changeOrdinal,
			Long conversationId) {
	}

	long insertFinding(UUID userId, Long sessionId, FindingSource source, String note, List<String> tags, Instant now) {
		return jdbc.sql("""
				INSERT INTO finding (user_id, research_session_id, source_type, filing_id, accession_number, company_name,
				                     form_type, report_date, section_key, section_title, block_refs, block_ordinal,
				                     quoted_text, previous_text, previous_accession, previous_block_ordinal, change_type,
				                     comparison_id, change_ordinal, conversation_id, note, tags, created_at, updated_at)
				VALUES (:user, :session, :type, :filing, :accession, :company, :form, :reportDate, :sectionKey,
				        :sectionTitle, CAST(:refs AS text[]), :ordinal, :quote, :previousText, :previousAccession,
				        :previousOrdinal, :changeType, :comparison, :changeOrdinal, :conversation, :note,
				        CAST(:tags AS text[]), :now, :now)
				RETURNING id
				""")
			.param("user", userId)
			.param("session", sessionId)
			.param("type", source.sourceType())
			.param("filing", source.filingId())
			.param("accession", source.accessionNumber())
			.param("company", source.companyName())
			.param("form", source.formType())
			.param("reportDate", source.reportDate() == null ? null : Date.valueOf(source.reportDate()))
			.param("sectionKey", source.sectionKey())
			.param("sectionTitle", source.sectionTitle())
			.param("refs", source.blockRefs().toArray(String[]::new))
			.param("ordinal", source.blockOrdinal())
			.param("quote", source.quotedText())
			.param("previousText", source.previousText())
			.param("previousAccession", source.previousAccession())
			.param("previousOrdinal", source.previousBlockOrdinal())
			.param("changeType", source.changeType())
			.param("comparison", source.comparisonId())
			.param("changeOrdinal", source.changeOrdinal())
			.param("conversation", source.conversationId())
			.param("note", note)
			.param("tags", tags.toArray(String[]::new))
			.param("now", Timestamp.from(now))
			.query(Long.class)
			.single();
	}

	int countFindings(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM finding WHERE user_id = :user").param("user", userId).query(Integer.class).single();
	}

	Optional<FindingView> finding(UUID userId, long id) {
		return jdbc.sql(SELECT_FINDING + "WHERE fd.id = :id AND fd.user_id = :user")
			.param("id", id)
			.param("user", userId)
			.query((rs, n) -> finding(rs))
			.optional();
	}

	/**
	 * @param sessionId a session's findings; null with {@code unfiledOnly} for findings in no session
	 * @param text matches note or quoted text, case-insensitively; may be null
	 * @param tag exact tag; may be null
	 */
	List<FindingView> findings(UUID userId, Long sessionId, boolean unfiledOnly, String text, String tag) {
		String pattern = text == null || text.isBlank() ? null
				: "%" + text.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
		return jdbc.sql(SELECT_FINDING + """
				WHERE fd.user_id = :user
				  AND (CAST(:session AS bigint) IS NULL OR fd.research_session_id = :session)
				  AND (NOT :unfiled OR fd.research_session_id IS NULL)
				  AND (CAST(:pattern AS text) IS NULL OR lower(fd.quoted_text) LIKE :pattern ESCAPE '\\'
				       OR lower(COALESCE(fd.note, '')) LIKE :pattern ESCAPE '\\')
				  AND (CAST(:tag AS text) IS NULL OR :tag = ANY (fd.tags))
				ORDER BY fd.created_at DESC, fd.id DESC
				LIMIT 500
				""")
			.param("user", userId)
			.param("session", sessionId)
			.param("unfiled", unfiledOnly)
			.param("pattern", pattern)
			.param("tag", tag)
			.query((rs, n) -> finding(rs))
			.list();
	}

	boolean updateFinding(UUID userId, long id, Long sessionId, String note, List<String> tags, Instant now) {
		return jdbc.sql("""
				UPDATE finding SET research_session_id = :session, note = :note, tags = CAST(:tags AS text[]),
				       updated_at = :now
				WHERE id = :id AND user_id = :user
				""")
			.param("session", sessionId)
			.param("note", note)
			.param("tags", tags.toArray(String[]::new))
			.param("now", Timestamp.from(now))
			.param("id", id)
			.param("user", userId)
			.update() == 1;
	}

	boolean deleteFinding(UUID userId, long id) {
		return jdbc.sql("DELETE FROM finding WHERE id = :id AND user_id = :user")
			.param("id", id)
			.param("user", userId)
			.update() == 1;
	}

	private static FindingView finding(ResultSet rs) throws SQLException {
		Date report = rs.getDate("report_date");
		return new FindingView(rs.getLong("id"), rs.getObject("research_session_id", Long.class),
				rs.getString("source_type"),
				new FilingRef(rs.getString("accession_number"), rs.getString("cik"), rs.getString("company_name"),
						rs.getString("form_type"), report == null ? null : report.toLocalDate()),
				rs.getString("section_key"), rs.getString("section_title"), strings(rs.getArray("block_refs")),
				rs.getInt("block_ordinal"), rs.getString("quoted_text"), rs.getString("previous_text"),
				rs.getString("previous_accession"), rs.getObject("previous_block_ordinal", Integer.class),
				rs.getString("change_type"), rs.getObject("comparison_id", Long.class),
				rs.getObject("change_ordinal", Integer.class), rs.getObject("conversation_id", Long.class),
				rs.getString("note"), strings(rs.getArray("tags")), rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
	}

}
