package com.filinglens.document.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.document.BlockText;
import com.filinglens.document.LocatedBlock;
import com.filinglens.document.SectionText;
import com.filinglens.document.internal.DocumentViews.BlockView;
import com.filinglens.document.internal.DocumentViews.SectionView;
import com.filinglens.document.internal.SectionDetector.DetectedSection;

@Repository
class DocumentRepository {

	/** Blocks are joined with this separator to form the normalized document text that offsets refer to. */
	static final String BLOCK_SEPARATOR = "\n\n";

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	DocumentRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
	}

	record ParseState(String status, int parserVersion, String detail, String annotatedBlobKey, Integer blockCount,
			Instant parsedAt) {
	}

	Optional<ParseState> state(long filingId) {
		return jdbc.sql("""
				SELECT status, parser_version, detail, annotated_blob_key, block_count, parsed_at
				FROM document_parse WHERE filing_id = :id
				""")
			.param("id", filingId)
			.query((rs, n) -> new ParseState(rs.getString("status"), rs.getInt("parser_version"),
					rs.getString("detail"), rs.getString("annotated_blob_key"), rs.getObject("block_count", Integer.class),
					instant(rs, "parsed_at")))
			.optional();
	}

	/**
	 * Marks the filing as parsing with the given version unless a parse with that
	 * version is already running or done.
	 * @return whether a parse should be queued
	 */
	boolean startParse(long filingId, int parserVersion, Instant now) {
		return jdbc.sql("""
				INSERT INTO document_parse (filing_id, status, parser_version, updated_at)
				VALUES (:id, 'PARSING', :version, :now)
				ON CONFLICT (filing_id) DO UPDATE
				SET status = 'PARSING', parser_version = EXCLUDED.parser_version, detail = NULL, updated_at = EXCLUDED.updated_at
				WHERE document_parse.status IN ('FAILED', 'UNSUPPORTED')
				   OR document_parse.parser_version < EXCLUDED.parser_version
				""")
			.param("id", filingId)
			.param("version", parserVersion)
			.param("now", Timestamp.from(now))
			.update() == 1;
	}

	void markFinished(long filingId, String status, String detail, Instant now) {
		jdbc.sql("""
				UPDATE document_parse SET status = :status, detail = :detail, updated_at = :now WHERE filing_id = :id
				""")
			.param("status", status)
			.param("detail", detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000))
			.param("now", Timestamp.from(now))
			.param("id", filingId)
			.update();
	}

	/** Replaces the filing's sections and blocks and records a successful parse. */
	void saveParsed(long filingId, int parserVersion, List<DetectedSection> sections, List<ParsedBlock> blocks,
			String annotatedBlobKey, Instant now) {
		jdbc.sql("DELETE FROM document_section WHERE filing_id = :id").param("id", filingId).update();

		List<Object[]> blockRows = new ArrayList<>(blocks.size());
		int offset = 0;
		int sectionIndex = 0;
		for (int s = 0; s < sections.size(); s++) {
			DetectedSection section = sections.get(s);
			long sectionId = jdbc.sql("""
					INSERT INTO document_section (filing_id, section_key, title, heading_text, ordinal,
					                              first_block_ordinal, last_block_ordinal, confidence)
					VALUES (:filingId, :key, :title, :heading, :ordinal, :first, :last, :confidence)
					RETURNING id
					""")
				.param("filingId", filingId)
				.param("key", section.key())
				.param("title", truncate(section.title(), 300))
				.param("heading", truncate(section.headingText(), 500))
				.param("ordinal", s + 1)
				.param("first", section.firstOrdinal())
				.param("last", section.lastOrdinal())
				.param("confidence", section.confidence().name())
				.query(Long.class)
				.single();
			int ordinalInSection = 0;
			for (; sectionIndex < blocks.size() && blocks.get(sectionIndex).ordinal() <= section.lastOrdinal(); sectionIndex++) {
				ParsedBlock block = blocks.get(sectionIndex);
				ordinalInSection++;
				int end = offset + block.text().length();
				blockRows.add(new Object[] { filingId, sectionId, block.ordinal(),
						section.key() + "/p" + ordinalInSection, block.type().name(), block.text(), sha256(block.text()),
						offset, end });
				offset = end + BLOCK_SEPARATOR.length();
			}
		}
		jdbcTemplate.batchUpdate("""
				INSERT INTO text_block (filing_id, section_id, ordinal, block_ref, block_type, text, content_hash,
				                        char_start, char_end)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", blockRows);

		jdbc.sql("""
				UPDATE document_parse
				SET status = 'PARSED', parser_version = :version, detail = NULL, annotated_blob_key = :key,
				    section_count = :sections, block_count = :blocks, text_length = :length, parsed_at = :now,
				    updated_at = :now
				WHERE filing_id = :id
				""")
			.param("version", parserVersion)
			.param("key", annotatedBlobKey)
			.param("sections", sections.size())
			.param("blocks", blocks.size())
			.param("length", Math.max(0, offset - BLOCK_SEPARATOR.length()))
			.param("now", Timestamp.from(now))
			.param("id", filingId)
			.update();
	}

	List<SectionView> sections(long filingId) {
		return jdbc.sql("""
				SELECT section_key, title, heading_text, ordinal, first_block_ordinal, last_block_ordinal, confidence
				FROM document_section WHERE filing_id = :id ORDER BY ordinal
				""")
			.param("id", filingId)
			.query((rs, n) -> new SectionView(rs.getString("section_key"), rs.getString("title"),
					rs.getString("heading_text"), rs.getInt("ordinal"), rs.getInt("first_block_ordinal"),
					rs.getInt("last_block_ordinal"), rs.getString("confidence")))
			.list();
	}

	/** @param sectionKey restricts to one section, or {@code null} for all */
	List<BlockView> blocks(long filingId, String sectionKey, int fromOrdinal, int limit) {
		return jdbc.sql("""
				SELECT b.ordinal, b.block_ref, s.section_key, b.block_type, b.text, b.content_hash, b.char_start, b.char_end
				FROM text_block b JOIN document_section s ON s.id = b.section_id
				WHERE b.filing_id = :id AND b.ordinal >= :from
				  AND (CAST(:section AS varchar) IS NULL OR s.section_key = :section)
				ORDER BY b.ordinal
				LIMIT :limit
				""")
			.param("id", filingId)
			.param("from", fromOrdinal)
			.param("section", sectionKey)
			.param("limit", limit)
			.query(DocumentRepository::block)
			.list();
	}

	/** All sections with their blocks, in document order. */
	List<SectionText> readAll(long filingId) {
		Map<String, List<BlockText>> blocksBySection = new LinkedHashMap<>();
		Map<String, String> titles = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT s.section_key, s.title, b.ordinal, b.block_ref, b.block_type, b.text, b.content_hash
				FROM document_section s JOIN text_block b ON b.section_id = s.id
				WHERE s.filing_id = :id
				ORDER BY b.ordinal
				""").param("id", filingId).query(rs -> {
			String key = rs.getString("section_key");
			titles.putIfAbsent(key, rs.getString("title"));
			blocksBySection.computeIfAbsent(key, k -> new ArrayList<>())
				.add(new BlockText(rs.getInt("ordinal"), rs.getString("block_ref"), rs.getString("block_type"),
						rs.getString("text"), rs.getString("content_hash")));
		});
		List<SectionText> sections = new ArrayList<>();
		blocksBySection.forEach((key, blocks) -> sections.add(new SectionText(key, titles.get(key), List.copyOf(blocks))));
		return sections;
	}

	Optional<LocatedBlock> locate(long filingId, int ordinal) {
		return jdbc.sql("""
				SELECT b.ordinal, b.block_ref, b.block_type, b.text, b.content_hash, s.section_key, s.title
				FROM text_block b JOIN document_section s ON s.id = b.section_id
				WHERE b.filing_id = :id AND b.ordinal = :ordinal
				""").param("id", filingId).param("ordinal", ordinal).query((rs, n) -> new LocatedBlock(
					new BlockText(rs.getInt("ordinal"), rs.getString("block_ref"), rs.getString("block_type"),
							rs.getString("text"), rs.getString("content_hash")),
					rs.getString("section_key"), rs.getString("title")))
			.optional();
	}

	Optional<BlockView> blockByRef(long filingId, String ref) {
		return jdbc.sql("""
				SELECT b.ordinal, b.block_ref, s.section_key, b.block_type, b.text, b.content_hash, b.char_start, b.char_end
				FROM text_block b JOIN document_section s ON s.id = b.section_id
				WHERE b.filing_id = :id AND b.block_ref = :ref
				""").param("id", filingId).param("ref", ref).query(DocumentRepository::block).optional();
	}

	private static BlockView block(ResultSet rs, int rowNum) throws SQLException {
		return new BlockView(rs.getInt("ordinal"), rs.getString("block_ref"), rs.getString("section_key"),
				rs.getString("block_type"), rs.getString("text"), rs.getString("content_hash"), rs.getInt("char_start"),
				rs.getInt("char_end"));
	}

	static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
