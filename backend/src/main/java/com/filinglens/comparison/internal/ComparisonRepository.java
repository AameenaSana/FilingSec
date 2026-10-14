package com.filinglens.comparison.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.comparison.internal.ComparisonEngine.Change;
import com.filinglens.comparison.internal.ComparisonEngine.ChangeType;
import com.filinglens.comparison.internal.ComparisonEngine.SectionResult;
import com.filinglens.comparison.internal.ComparisonViews.BlockSide;
import com.filinglens.comparison.internal.ComparisonViews.ChangeView;
import com.filinglens.comparison.internal.ComparisonViews.SectionSummary;
import com.filinglens.comparison.internal.ComparisonViews.Stats;
import com.filinglens.comparison.internal.ComparisonViews.Status;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ComparisonRepository {

	private static final TypeReference<List<WordDiff.Op>> OPS = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	private final JsonMapper json;

	ComparisonRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, JsonMapper json) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.json = json;
	}

	record ComparisonRow(long id, long baseFilingId, long targetFilingId, Status status, String detail, Stats stats,
			Instant computedAt) {
	}

	/** @return the id of the existing or newly created comparison */
	long findOrCreate(long baseFilingId, long targetFilingId, int version, Instant now) {
		jdbc.sql("""
				INSERT INTO filing_comparison (base_filing_id, target_filing_id, algorithm_version, status, created_at,
				                               updated_at)
				VALUES (:base, :target, :version, 'WAITING', :now, :now)
				ON CONFLICT (base_filing_id, target_filing_id, algorithm_version) DO NOTHING
				""")
			.param("base", baseFilingId)
			.param("target", targetFilingId)
			.param("version", version)
			.param("now", Timestamp.from(now))
			.update();
		return jdbc.sql("""
				SELECT id FROM filing_comparison
				WHERE base_filing_id = :base AND target_filing_id = :target AND algorithm_version = :version
				""")
			.param("base", baseFilingId)
			.param("target", targetFilingId)
			.param("version", version)
			.query(Long.class)
			.single();
	}

	Optional<ComparisonRow> find(long id) {
		return jdbc.sql("""
				SELECT id, base_filing_id, target_filing_id, status, detail, stats::text AS stats, computed_at
				FROM filing_comparison WHERE id = :id
				""").param("id", id).query((rs, n) -> row(rs)).optional();
	}

	/** Comparisons waiting for documents that involve this filing. */
	List<Long> waitingFor(long filingId) {
		return jdbc.sql("""
				SELECT id FROM filing_comparison
				WHERE status = 'WAITING' AND (base_filing_id = :id OR target_filing_id = :id)
				""").param("id", filingId).query(Long.class).list();
	}

	/** Moves a comparison between states only if it is in the expected one. */
	boolean transition(long id, Status from, Status to, String detail, Instant now) {
		return jdbc.sql("""
				UPDATE filing_comparison SET status = :to, detail = :detail, updated_at = :now
				WHERE id = :id AND status = :from
				""")
			.param("to", to.name())
			.param("detail", detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000))
			.param("now", Timestamp.from(now))
			.param("id", id)
			.param("from", from.name())
			.update() == 1;
	}

	void markFailed(long id, String detail, Instant now) {
		jdbc.sql("UPDATE filing_comparison SET status = 'FAILED', detail = :detail, updated_at = :now WHERE id = :id")
			.param("detail", detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000))
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update();
	}

	/** Replaces any earlier result and marks the comparison READY. */
	void saveResult(long id, List<SectionResult> sections, Instant now) {
		jdbc.sql("DELETE FROM section_comparison WHERE comparison_id = :id").param("id", id).update();
		int added = 0;
		int removed = 0;
		int modified = 0;
		int moved = 0;
		int unchanged = 0;
		int numericOnly = 0;
		for (int s = 0; s < sections.size(); s++) {
			SectionResult section = sections.get(s);
			long sectionId = jdbc.sql("""
					INSERT INTO section_comparison (comparison_id, section_key, title, ordinal, alignment, added_count,
					    removed_count, modified_count, moved_count, unchanged_count, numeric_only_count)
					VALUES (:id, :key, :title, :ordinal, :alignment, :added, :removed, :modified, :moved, :unchanged,
					        :numericOnly)
					RETURNING id
					""")
				.param("id", id)
				.param("key", section.key())
				.param("title", section.title().length() <= 300 ? section.title() : section.title().substring(0, 300))
				.param("ordinal", s + 1)
				.param("alignment", section.alignment().name())
				.param("added", section.count(ChangeType.ADDED))
				.param("removed", section.count(ChangeType.REMOVED))
				.param("modified", section.count(ChangeType.MODIFIED))
				.param("moved", section.count(ChangeType.MOVED))
				.param("unchanged", section.unchanged())
				.param("numericOnly", section.numericOnly())
				.query(Long.class)
				.single();
			List<Object[]> rows = new ArrayList<>();
			for (int c = 0; c < section.changes().size(); c++) {
				Change change = section.changes().get(c);
				rows.add(new Object[] { sectionId, c + 1, change.type().name(), change.numericOnly(), change.similarity(),
						change.base() == null ? null : change.base().ordinal(),
						change.base() == null ? null : change.base().ref(),
						change.base() == null ? null : change.base().text(),
						change.target() == null ? null : change.target().ordinal(),
						change.target() == null ? null : change.target().ref(),
						change.target() == null ? null : change.target().text(),
						change.wordDiff() == null ? null : json.writeValueAsString(change.wordDiff()) });
			}
			jdbcTemplate.batchUpdate("""
					INSERT INTO text_change (section_comparison_id, ordinal, change_type, numeric_only, similarity,
					    base_block_ordinal, base_block_ref, base_text, target_block_ordinal, target_block_ref,
					    target_text, word_diff)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
					""", rows);
			added += (int) section.count(ChangeType.ADDED);
			removed += (int) section.count(ChangeType.REMOVED);
			modified += (int) section.count(ChangeType.MODIFIED);
			moved += (int) section.count(ChangeType.MOVED);
			unchanged += section.unchanged();
			numericOnly += (int) section.numericOnly();
		}
		Stats stats = new Stats(added, removed, modified, moved, unchanged, numericOnly);
		jdbc.sql("""
				UPDATE filing_comparison
				SET status = 'READY', detail = NULL, stats = CAST(:stats AS jsonb), computed_at = :now, updated_at = :now
				WHERE id = :id
				""").param("stats", json.writeValueAsString(stats)).param("now", Timestamp.from(now)).param("id", id).update();
	}

	List<SectionSummary> sections(long id) {
		return jdbc.sql("""
				SELECT section_key, title, alignment, added_count, removed_count, modified_count, moved_count,
				       unchanged_count, numeric_only_count
				FROM section_comparison WHERE comparison_id = :id ORDER BY ordinal
				""")
			.param("id", id)
			.query((rs, n) -> new SectionSummary(rs.getString("section_key"), rs.getString("title"),
					rs.getString("alignment"), rs.getInt("added_count"), rs.getInt("removed_count"),
					rs.getInt("modified_count"), rs.getInt("moved_count"), rs.getInt("unchanged_count"),
					rs.getInt("numeric_only_count")))
			.list();
	}

	/** @return empty if the section is not part of the comparison */
	Optional<List<ChangeView>> changes(long id, String sectionKey, boolean includeNumericOnly) {
		Optional<Long> sectionId = jdbc.sql("""
				SELECT id FROM section_comparison WHERE comparison_id = :id AND section_key = :key
				""").param("id", id).param("key", sectionKey).query(Long.class).optional();
		return sectionId.map(sid -> jdbc.sql("""
				SELECT ordinal, change_type, numeric_only, similarity, base_block_ordinal, base_block_ref, base_text,
				       target_block_ordinal, target_block_ref, target_text, word_diff::text AS word_diff
				FROM text_change
				WHERE section_comparison_id = :sid AND (:numericOnly OR NOT numeric_only)
				ORDER BY ordinal
				""").param("sid", sid).param("numericOnly", includeNumericOnly).query((rs, n) -> change(rs)).list());
	}

	private ChangeView change(ResultSet rs) throws SQLException {
		Integer baseOrdinal = rs.getObject("base_block_ordinal", Integer.class);
		Integer targetOrdinal = rs.getObject("target_block_ordinal", Integer.class);
		String diff = rs.getString("word_diff");
		Float similarity = rs.getObject("similarity", Float.class);
		return new ChangeView(rs.getInt("ordinal"), rs.getString("change_type"), rs.getBoolean("numeric_only"),
				similarity == null ? null : Math.round(similarity * 1000) / 1000.0,
				baseOrdinal == null ? null
						: new BlockSide(baseOrdinal, rs.getString("base_block_ref"), rs.getString("base_text")),
				targetOrdinal == null ? null
						: new BlockSide(targetOrdinal, rs.getString("target_block_ref"), rs.getString("target_text")),
				diff == null ? null : json.readValue(diff, OPS));
	}

	private ComparisonRow row(ResultSet rs) throws SQLException {
		String stats = rs.getString("stats");
		Timestamp computedAt = rs.getTimestamp("computed_at");
		return new ComparisonRow(rs.getLong("id"), rs.getLong("base_filing_id"), rs.getLong("target_filing_id"),
				Status.valueOf(rs.getString("status")), rs.getString("detail"),
				stats == null ? null : json.readValue(stats, Stats.class),
				computedAt == null ? null : computedAt.toInstant());
	}

}
