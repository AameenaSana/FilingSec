package com.filinglens.research.internal;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.ai.AiModel;
import com.filinglens.research.internal.CitationMapper.MappedCitation;
import com.filinglens.research.internal.ResearchViews.CitationView;
import com.filinglens.research.internal.ResearchViews.ConversationSummary;
import com.filinglens.research.internal.ResearchViews.FilingInfo;
import com.filinglens.research.internal.ResearchViews.MessageView;
import com.filinglens.research.internal.Retriever.Retrieved;

/** Every conversation query is scoped to the owning user. */
@Repository
class ConversationRepository {

	private static final String FILING_INFO = """
			SELECT f.id, f.accession_number, c.cik, c.name, f.form_type, f.report_date, f.filing_date
			FROM filing f JOIN company c ON c.id = f.company_id
			""";

	private final JdbcClient jdbc;

	ConversationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record ConversationRow(long id, String title, Instant updatedAt) {
	}

	long create(UUID userId, String title, List<Long> filingIds, Instant now) {
		long id = jdbc.sql("""
				INSERT INTO conversation (user_id, title, created_at, updated_at) VALUES (:user, :title, :now, :now)
				RETURNING id
				""").param("user", userId).param("title", title).param("now", Timestamp.from(now)).query(Long.class).single();
		for (int i = 0; i < filingIds.size(); i++) {
			jdbc.sql("INSERT INTO conversation_filing (conversation_id, filing_id, position) VALUES (:c, :f, :p)")
				.param("c", id)
				.param("f", filingIds.get(i))
				.param("p", i)
				.update();
		}
		return id;
	}

	Optional<ConversationRow> find(UUID userId, long id) {
		return jdbc.sql("SELECT id, title, updated_at FROM conversation WHERE id = :id AND user_id = :user")
			.param("id", id)
			.param("user", userId)
			.query((rs, n) -> new ConversationRow(rs.getLong("id"), rs.getString("title"),
					rs.getTimestamp("updated_at").toInstant()))
			.optional();
	}

	List<ConversationSummary> list(UUID userId, int limit) {
		List<ConversationRow> rows = jdbc.sql("""
				SELECT id, title, updated_at FROM conversation WHERE user_id = :user ORDER BY updated_at DESC, id DESC
				LIMIT :limit
				""")
			.param("user", userId)
			.param("limit", limit)
			.query((rs, n) -> new ConversationRow(rs.getLong("id"), rs.getString("title"),
					rs.getTimestamp("updated_at").toInstant()))
			.list();
		List<ConversationSummary> result = new ArrayList<>();
		for (ConversationRow row : rows) {
			result.add(new ConversationSummary(row.id(), row.title(), filings(row.id()), row.updatedAt()));
		}
		return result;
	}

	boolean rename(UUID userId, long id, String title, Instant now) {
		return jdbc.sql("UPDATE conversation SET title = :title, updated_at = :now WHERE id = :id AND user_id = :user")
			.param("title", title)
			.param("now", Timestamp.from(now))
			.param("id", id)
			.param("user", userId)
			.update() == 1;
	}

	boolean delete(UUID userId, long id) {
		return jdbc.sql("DELETE FROM conversation WHERE id = :id AND user_id = :user")
			.param("id", id)
			.param("user", userId)
			.update() == 1;
	}

	List<Long> filingIds(long conversationId) {
		return jdbc.sql("SELECT filing_id FROM conversation_filing WHERE conversation_id = :id ORDER BY position")
			.param("id", conversationId)
			.query(Long.class)
			.list();
	}

	List<FilingInfo> filings(long conversationId) {
		return jdbc.sql(FILING_INFO + """
				JOIN conversation_filing cf ON cf.filing_id = f.id
				WHERE cf.conversation_id = :id ORDER BY cf.position
				""").param("id", conversationId).query((rs, n) -> filingInfo(rs)).list();
	}

	Map<Long, FilingInfo> filingInfo(List<Long> filingIds) {
		Map<Long, FilingInfo> result = new HashMap<>();
		jdbc.sql(FILING_INFO + "WHERE f.id = ANY (CAST(:ids AS bigint[]))")
			.param("ids", filingIds.toArray(Long[]::new))
			.query((rs, n) -> Map.entry(rs.getLong("id"), filingInfo(rs)))
			.list()
			.forEach(e -> result.put(e.getKey(), e.getValue()));
		return result;
	}

	// --- Messages ----------------------------------------------------------------

	long addMessage(long conversationId, String role, String content, String status, Instant now) {
		jdbc.sql("UPDATE conversation SET updated_at = :now WHERE id = :id")
			.param("now", Timestamp.from(now))
			.param("id", conversationId)
			.update();
		return jdbc.sql("""
				INSERT INTO message (conversation_id, role, content, status, created_at)
				VALUES (:c, :role, :content, :status, :now) RETURNING id
				""")
			.param("c", conversationId)
			.param("role", role)
			.param("content", content)
			.param("status", status)
			.param("now", Timestamp.from(now))
			.query(Long.class)
			.single();
	}

	void finishMessage(long messageId, String content, String status, String model, Long inputTokens,
			Long outputTokens, Long cacheReadTokens, String error) {
		jdbc.sql("""
				UPDATE message SET content = :content, status = :status, model = :model, input_tokens = :input,
				       output_tokens = :output, cache_read_tokens = :cacheRead, error = :error
				WHERE id = :id
				""")
			.param("content", content)
			.param("status", status)
			.param("model", model)
			.param("input", inputTokens == null ? null : inputTokens.intValue())
			.param("output", outputTokens == null ? null : outputTokens.intValue())
			.param("cacheRead", cacheReadTokens == null ? null : cacheReadTokens.intValue())
			.param("error", error == null || error.length() <= 1000 ? error : error.substring(0, 1000))
			.param("id", messageId)
			.update();
	}

	/** Earlier completed turns, oldest first, for conversational context. */
	List<AiModel.Turn> history(long conversationId, long beforeMessageId, int limit) {
		List<AiModel.Turn> turns = new ArrayList<>(jdbc.sql("""
				SELECT role, content FROM message
				WHERE conversation_id = :c AND id < :before AND status = 'COMPLETE'
				ORDER BY id DESC LIMIT :limit
				""")
			.param("c", conversationId)
			.param("before", beforeMessageId)
			.param("limit", limit)
			.query((rs, n) -> new AiModel.Turn("USER".equals(rs.getString("role")), rs.getString("content")))
			.list());
		Collections.reverse(turns);
		// The API requires alternating turns starting with the user.
		while (!turns.isEmpty() && !turns.getFirst().fromUser()) {
			turns.removeFirst();
		}
		List<AiModel.Turn> alternating = new ArrayList<>();
		for (AiModel.Turn turn : turns) {
			if (alternating.isEmpty() || alternating.getLast().fromUser() != turn.fromUser()) {
				alternating.add(turn);
			}
		}
		if (!alternating.isEmpty() && alternating.getLast().fromUser()) {
			alternating.removeLast();
		}
		return alternating;
	}

	void saveCitations(long messageId, List<MappedCitation> citations) {
		for (MappedCitation c : citations) {
			jdbc.sql("""
					INSERT INTO citation (message_id, ordinal, filing_id, section_key, section_title, block_refs,
					                      first_block_ordinal, cited_text, answer_start, answer_end, verified)
					VALUES (:m, :ordinal, :filing, :key, :title, CAST(:refs AS text[]), :first, :cited, :start, :end,
					        :verified)
					""")
				.param("m", messageId)
				.param("ordinal", c.ordinal())
				.param("filing", c.chunk().filingId())
				.param("key", c.chunk().sectionKey())
				.param("title", c.chunk().sectionTitle())
				.param("refs", c.blockRefs().toArray(String[]::new))
				.param("first", c.firstBlockOrdinal())
				.param("cited", c.citedText())
				.param("start", c.answerStart())
				.param("end", c.answerEnd())
				.param("verified", c.verified())
				.update();
		}
	}

	void saveTrace(long messageId, List<Retrieved> retrieved) {
		for (int i = 0; i < retrieved.size(); i++) {
			Retrieved r = retrieved.get(i);
			jdbc.sql("""
					INSERT INTO retrieval_trace (message_id, rank, chunk_id, filing_id, score, vector_rank, text_rank)
					VALUES (:m, :rank, :chunk, :filing, :score, :vector, :text)
					""")
				.param("m", messageId)
				.param("rank", i + 1)
				.param("chunk", r.chunk().id())
				.param("filing", r.chunk().filingId())
				.param("score", (float) r.score())
				.param("vector", r.vectorRank())
				.param("text", r.textRank())
				.update();
		}
	}

	List<MessageView> messages(long conversationId) {
		Map<Long, List<CitationView>> citations = new HashMap<>();
		jdbc.sql("""
				SELECT ci.id AS citation_id, ci.message_id, ci.ordinal, ci.section_key, ci.section_title, ci.block_refs, ci.first_block_ordinal,
				       ci.cited_text, ci.answer_start, ci.answer_end, ci.verified,
				       f.id, f.accession_number, c.cik, c.name, f.form_type, f.report_date, f.filing_date
				FROM citation ci
				JOIN message m ON m.id = ci.message_id
				JOIN filing f ON f.id = ci.filing_id
				JOIN company c ON c.id = f.company_id
				WHERE m.conversation_id = :c
				ORDER BY ci.message_id, ci.id
				""").param("c", conversationId).query((rs, n) -> {
			citations.computeIfAbsent(rs.getLong("message_id"), k -> new ArrayList<>())
				.add(new CitationView(rs.getLong("citation_id"), rs.getInt("ordinal"), filingInfo(rs), rs.getString("section_key"),
						rs.getString("section_title"), strings(rs.getArray("block_refs")),
						rs.getInt("first_block_ordinal"), rs.getString("cited_text"), rs.getInt("answer_start"),
						rs.getInt("answer_end"), rs.getBoolean("verified")));
			return null;
		}).list();
		return jdbc.sql("""
				SELECT id, role, content, status, created_at FROM message WHERE conversation_id = :c ORDER BY id
				""")
			.param("c", conversationId)
			.query((rs, n) -> new MessageView(rs.getLong("id"), rs.getString("role"), rs.getString("content"),
					rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
					citations.getOrDefault(rs.getLong("id"), List.of())))
			.list();
	}

	private static FilingInfo filingInfo(ResultSet rs) throws SQLException {
		Date report = rs.getDate("report_date");
		return new FilingInfo(rs.getString("accession_number"), rs.getString("cik"), rs.getString("name"),
				rs.getString("form_type"), report == null ? null : report.toLocalDate(),
				rs.getDate("filing_date").toLocalDate());
	}

	private static List<String> strings(Array array) throws SQLException {
		return Arrays.asList((String[]) array.getArray());
	}

}
