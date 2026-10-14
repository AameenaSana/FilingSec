package com.filinglens.filing.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.filinglens.edgar.Accession;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;

@Component
class FilingCatalogAdapter implements FilingCatalog {

	private static final String SELECT = """
			SELECT f.id, f.accession_number, c.cik, c.name, f.form_type, f.primary_document,
			       (SELECT d.blob_key FROM filing_document d
			        WHERE d.filing_id = f.id AND d.is_primary AND d.blob_key IS NOT NULL LIMIT 1) AS blob_key
			FROM filing f JOIN company c ON c.id = f.company_id
			""";

	private final JdbcClient jdbc;

	FilingCatalogAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<FilingRef> findByAccession(String accessionNumber) {
		if (!Accession.isValid(accessionNumber)) {
			return Optional.empty();
		}
		return jdbc.sql(SELECT + "WHERE f.accession_number = :a")
			.param("a", Accession.normalize(accessionNumber))
			.query((rs, n) -> ref(rs))
			.optional();
	}

	@Override
	public Optional<FilingRef> findById(long filingId) {
		return jdbc.sql(SELECT + "WHERE f.id = :id").param("id", filingId).query((rs, n) -> ref(rs)).optional();
	}

	private static FilingRef ref(ResultSet rs) throws SQLException {
		return new FilingRef(rs.getLong("id"), rs.getString("accession_number"), rs.getString("cik"),
				rs.getString("name"), rs.getString("form_type"), rs.getString("primary_document"),
				rs.getString("blob_key"));
	}

}
