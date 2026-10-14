package com.filinglens.filing.internal;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.filinglens.edgar.Accession;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;

@Component
class FilingCatalogAdapter implements FilingCatalog {

	private static final String SELECT = """
			SELECT f.id, f.company_id, f.accession_number, c.cik, c.name, f.form_type, f.filing_date, f.report_date,
			       f.primary_document, f.status,
			       (SELECT d.blob_key FROM filing_document d
			        WHERE d.filing_id = f.id AND d.is_primary AND d.blob_key IS NOT NULL LIMIT 1) AS blob_key
			FROM filing f JOIN company c ON c.id = f.company_id
			""";

	private final JdbcClient jdbc;

	private final FilingService filings;

	FilingCatalogAdapter(JdbcClient jdbc, FilingService filings) {
		this.jdbc = jdbc;
		this.filings = filings;
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

	@Override
	public List<FilingRef> comparableFilings(long filingId, int limit) {
		return jdbc.sql(SELECT + """
				JOIN filing self ON self.id = :id
				WHERE f.company_id = self.company_id AND f.id <> self.id
				  AND replace(f.form_type, '/A', '') = replace(self.form_type, '/A', '')
				ORDER BY COALESCE(f.report_date, f.filing_date) DESC, f.filing_date DESC
				LIMIT :limit
				""").param("id", filingId).param("limit", limit).query((rs, n) -> ref(rs)).list();
	}

	@Override
	public void requestDocuments(long filingId) {
		findById(filingId).ifPresent(filing -> filings.requestFetch(filing.accessionNumber()));
	}

	private static FilingRef ref(ResultSet rs) throws SQLException {
		return new FilingRef(rs.getLong("id"), rs.getLong("company_id"), rs.getString("accession_number"),
				rs.getString("cik"), rs.getString("name"), rs.getString("form_type"), date(rs, "filing_date"),
				date(rs, "report_date"), rs.getString("primary_document"), rs.getString("blob_key"),
				rs.getString("status"));
	}

	private static LocalDate date(ResultSet rs, String column) throws SQLException {
		Date date = rs.getDate(column);
		return date == null ? null : date.toLocalDate();
	}

}
