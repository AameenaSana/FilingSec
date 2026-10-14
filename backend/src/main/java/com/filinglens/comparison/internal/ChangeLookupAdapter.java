package com.filinglens.comparison.internal;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.filinglens.comparison.ChangeLookup;
import com.filinglens.comparison.ChangeSnapshot;

@Component
class ChangeLookupAdapter implements ChangeLookup {

	private final JdbcClient jdbc;

	ChangeLookupAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<ChangeSnapshot> find(long comparisonId, String sectionKey, int changeOrdinal) {
		return jdbc.sql("""
				SELECT tc.ordinal, tc.change_type, sc.section_key, sc.title, co.name AS company, tf.form_type,
				       fc.base_filing_id, bf.accession_number AS base_accession, bf.report_date AS base_report,
				       tc.base_block_ref, tc.base_block_ordinal, tc.base_text,
				       fc.target_filing_id, tf.accession_number AS target_accession, tf.report_date AS target_report,
				       tc.target_block_ref, tc.target_block_ordinal, tc.target_text
				FROM text_change tc
				JOIN section_comparison sc ON sc.id = tc.section_comparison_id
				JOIN filing_comparison fc ON fc.id = sc.comparison_id
				JOIN filing bf ON bf.id = fc.base_filing_id
				JOIN filing tf ON tf.id = fc.target_filing_id
				JOIN company co ON co.id = tf.company_id
				WHERE fc.id = :id AND sc.section_key = :key AND tc.ordinal = :ordinal
				""")
			.param("id", comparisonId)
			.param("key", sectionKey)
			.param("ordinal", changeOrdinal)
			.query((rs, n) -> new ChangeSnapshot(comparisonId, rs.getInt("ordinal"), rs.getString("change_type"),
					rs.getString("section_key"), rs.getString("title"), rs.getString("company"),
					rs.getString("form_type"), side(rs, "base"), side(rs, "target")))
			.optional();
	}

	private static ChangeSnapshot.Side side(ResultSet rs, String prefix) throws SQLException {
		String text = rs.getString(prefix + "_text");
		if (text == null) {
			return null;
		}
		Date report = rs.getDate(prefix + "_report");
		return new ChangeSnapshot.Side(rs.getLong(prefix + "_filing_id"), rs.getString(prefix + "_accession"),
				report == null ? null : report.toLocalDate(), rs.getString(prefix + "_block_ref"),
				rs.getInt(prefix + "_block_ordinal"), text);
	}

}
