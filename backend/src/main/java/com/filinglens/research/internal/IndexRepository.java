package com.filinglens.research.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.document.BlockText;

@Repository
class IndexRepository {

	private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]{2,}");

	private final JdbcClient jdbc;

	IndexRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record IndexState(String status, boolean embedded, Integer chunkCount, Integer parserVersion, String detail) {
	}

	/** A stored chunk with its blocks, as given to the model. */
	record ChunkRow(long id, long filingId, String sectionKey, String sectionTitle, List<Integer> blockOrdinals,
			List<String> blockRefs, List<String> blockTexts) {
	}

	Optional<IndexState> state(long filingId) {
		return jdbc.sql("""
				SELECT status, embedded, chunk_count, parser_version, detail FROM filing_index WHERE filing_id = :id
				""")
			.param("id", filingId)
			.query((rs, n) -> new IndexState(rs.getString("status"), rs.getBoolean("embedded"),
					rs.getObject("chunk_count", Integer.class), rs.getObject("parser_version", Integer.class),
					rs.getString("detail")))
			.optional();
	}

	/**
	 * Marks the filing as indexing unless it is already indexing or indexed for this
	 * parser version.
	 * @return whether indexing should be queued
	 */
	boolean startIndex(long filingId, int parserVersion, Instant now) {
		return jdbc.sql("""
				INSERT INTO filing_index (filing_id, status, parser_version, updated_at)
				VALUES (:id, 'INDEXING', :version, :now)
				ON CONFLICT (filing_id) DO UPDATE
				SET status = 'INDEXING', parser_version = EXCLUDED.parser_version, detail = NULL, updated_at = EXCLUDED.updated_at
				WHERE filing_index.status = 'FAILED'
				   OR (filing_index.status = 'INDEXED' AND filing_index.parser_version < EXCLUDED.parser_version)
				""").param("id", filingId).param("version", parserVersion).param("now", Timestamp.from(now)).update() == 1;
	}

	/** Replaces the filing's chunks. {@code embeddings} is null when no embedding provider is configured. */
	void replaceChunks(long filingId, List<Chunker.Chunk> chunks, List<float[]> embeddings, String embeddingModel,
			Instant now) {
		jdbc.sql("DELETE FROM text_chunk WHERE filing_id = :id").param("id", filingId).update();
		for (int i = 0; i < chunks.size(); i++) {
			Chunker.Chunk chunk = chunks.get(i);
			List<BlockText> blocks = chunk.blocks();
			jdbc.sql("""
					INSERT INTO text_chunk (filing_id, ordinal, section_key, section_title, block_ordinals, block_refs,
					                        block_texts, text, embedding, embedding_model)
					VALUES (:filingId, :ordinal, :key, :title, CAST(:ordinals AS int[]), CAST(:refs AS text[]),
					        CAST(:texts AS text[]), :text, CAST(:embedding AS vector), :model)
					""")
				.param("filingId", filingId)
				.param("ordinal", chunk.ordinal())
				.param("key", chunk.sectionKey())
				.param("title", truncate(chunk.sectionTitle(), 300))
				.param("ordinals", blocks.stream().map(BlockText::ordinal).toArray(Integer[]::new))
				.param("refs", blocks.stream().map(BlockText::ref).toArray(String[]::new))
				.param("texts", blocks.stream().map(BlockText::text).toArray(String[]::new))
				.param("text", chunk.text())
				.param("embedding", embeddings == null ? null : vectorLiteral(embeddings.get(i)))
				.param("model", embeddings == null ? null : embeddingModel)
				.update();
		}
		jdbc.sql("""
				UPDATE filing_index SET status = 'INDEXED', chunk_count = :count, embedded = :embedded,
				       embedding_model = :model, detail = NULL, indexed_at = :now, updated_at = :now
				WHERE filing_id = :id
				""")
			.param("count", chunks.size())
			.param("embedded", embeddings != null)
			.param("model", embeddings == null ? null : embeddingModel)
			.param("now", Timestamp.from(now))
			.param("id", filingId)
			.update();
	}

	void markFailed(long filingId, String detail, Instant now) {
		jdbc.sql("UPDATE filing_index SET status = 'FAILED', detail = :detail, updated_at = :now WHERE filing_id = :id")
			.param("detail", truncate(detail, 1000))
			.param("now", Timestamp.from(now))
			.param("id", filingId)
			.update();
	}

	/** Chunk ids by full-text relevance; any query word may match. */
	List<Long> textSearch(List<Long> filingIds, String query, int limit) {
		String orQuery = orQuery(query);
		if (orQuery.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id FROM text_chunk, to_tsquery('english', :q) query
				WHERE filing_id = ANY (CAST(:ids AS bigint[])) AND tsv @@ query
				ORDER BY ts_rank_cd(tsv, query) DESC, id
				LIMIT :limit
				""")
			.param("q", orQuery)
			.param("ids", filingIds.toArray(Long[]::new))
			.param("limit", limit)
			.query(Long.class)
			.list();
	}

	/**
	 * Chunk ids by cosine similarity. The distance expression is wrapped so the
	 * planner computes it exactly over the conversation's filings instead of using
	 * the approximate index, whose candidates could all belong to other filings.
	 */
	List<Long> vectorSearch(List<Long> filingIds, float[] query, int limit) {
		return jdbc.sql("""
				SELECT id FROM text_chunk
				WHERE filing_id = ANY (CAST(:ids AS bigint[])) AND embedding IS NOT NULL
				ORDER BY (embedding <=> CAST(:v AS vector)) + 0, id
				LIMIT :limit
				""")
			.param("ids", filingIds.toArray(Long[]::new))
			.param("v", vectorLiteral(query))
			.param("limit", limit)
			.query(Long.class)
			.list();
	}

	/** Loads chunks, returned in the order of {@code ids}. */
	List<ChunkRow> load(List<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<Long, ChunkRow> byId = new HashMap<>();
		jdbc.sql("""
				SELECT id, filing_id, section_key, section_title, block_ordinals, block_refs, block_texts
				FROM text_chunk WHERE id = ANY (CAST(:ids AS bigint[]))
				""").param("ids", ids.toArray(Long[]::new)).query((rs, n) -> chunk(rs)).list().forEach(c -> byId.put(c.id(), c));
		List<ChunkRow> ordered = new ArrayList<>();
		for (long id : ids) {
			if (byId.containsKey(id)) {
				ordered.add(byId.get(id));
			}
		}
		return ordered;
	}

	static String orQuery(String query) {
		Matcher matcher = WORD.matcher(query);
		List<String> words = new ArrayList<>();
		while (matcher.find() && words.size() < 32) {
			words.add(matcher.group().toLowerCase());
		}
		return words.stream().distinct().collect(Collectors.joining(" | "));
	}

	static String vectorLiteral(float[] vector) {
		StringBuilder literal = new StringBuilder(vector.length * 10).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				literal.append(',');
			}
			literal.append(vector[i]);
		}
		return literal.append(']').toString();
	}

	private static ChunkRow chunk(ResultSet rs) throws SQLException {
		return new ChunkRow(rs.getLong("id"), rs.getLong("filing_id"), rs.getString("section_key"),
				rs.getString("section_title"), Arrays.asList((Integer[]) array(rs.getArray("block_ordinals"))),
				Arrays.asList((String[]) array(rs.getArray("block_refs"))),
				Arrays.asList((String[]) array(rs.getArray("block_texts"))));
	}

	private static Object[] array(Array array) throws SQLException {
		return (Object[]) array.getArray();
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
