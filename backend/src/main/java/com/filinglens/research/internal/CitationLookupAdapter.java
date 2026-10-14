package com.filinglens.research.internal;

import java.sql.Date;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.filinglens.research.CitationLookup;
import com.filinglens.research.CitationSnapshot;

@Component
class CitationLookupAdapter implements CitationLookup {

	private final JdbcClient jdbc;

	CitationLookupAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<CitationSnapshot> find(UUID userId, long citationId) {
		return jdbc.sql("""
				SELECT ci.id, m.conversation_id, f.id AS filing_id, f.accession_number, c.name, f.form_type, f.report_date,
				       ci.section_key, ci.section_title, ci.block_refs, ci.first_block_ordinal, ci.cited_text
				FROM citation ci
				JOIN message m ON m.id = ci.message_id
				JOIN conversation cv ON cv.id = m.conversation_id
				JOIN filing f ON f.id = ci.filing_id
				JOIN company c ON c.id = f.company_id
				WHERE ci.id = :id AND cv.user_id = :user
				""").param("id", citationId).param("user", userId).query((rs, n) -> {
			Date report = rs.getDate("report_date");
			return new CitationSnapshot(rs.getLong("id"), rs.getLong("conversation_id"), rs.getLong("filing_id"),
					rs.getString("accession_number"), rs.getString("name"), rs.getString("form_type"),
					report == null ? null : report.toLocalDate(), rs.getString("section_key"),
					rs.getString("section_title"), Arrays.asList((String[]) rs.getArray("block_refs").getArray()),
					rs.getInt("first_block_ordinal"), rs.getString("cited_text"));
		}).optional();
	}

}
