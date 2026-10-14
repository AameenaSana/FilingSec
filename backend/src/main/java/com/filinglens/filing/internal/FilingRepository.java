package com.filinglens.filing.internal;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.filinglens.edgar.EdgarUrls;
import com.filinglens.edgar.FilingIndexDocument;
import com.filinglens.edgar.FilingRecord;
import com.filinglens.filing.internal.FilingViews.CompanyInfo;
import com.filinglens.filing.internal.FilingViews.FilingDetail;
import com.filinglens.filing.internal.FilingViews.FilingDocumentView;
import com.filinglens.filing.internal.FilingViews.FilingSummary;
import com.filinglens.filing.internal.FilingViews.Status;
import com.filinglens.platform.storage.BlobStore.StoredBlob;

@Repository
class FilingRepository {

	private final JdbcClient jdbc;

	private final JdbcTemplate jdbcTemplate;

	FilingRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
	}

	// --- Discovery -------------------------------------------------------------

	/** Inserts filings not yet known. Filing metadata is immutable, so existing rows are left as they are. */
	int insertNew(long companyId, List<FilingRecord> filings, Instant now) {
		Timestamp ts = Timestamp.from(now);
		List<Object[]> rows = new ArrayList<>(filings.size());
		for (FilingRecord f : filings) {
			rows.add(new Object[] { companyId, f.accessionNumber(), truncate(f.form(), 20), Date.valueOf(f.filingDate()),
					f.reportDate() == null ? null : Date.valueOf(f.reportDate()),
					f.acceptanceDateTime() == null ? null : Timestamp.from(f.acceptanceDateTime()),
					EdgarUrls.isValidFilename(f.primaryDocument()) ? f.primaryDocument() : null,
					truncate(f.primaryDocDescription(), 500), truncate(f.items(), 200), f.size(), f.xbrl(),
					f.inlineXbrl(), truncate(f.fileNumber(), 50), truncate(f.filmNumber(), 50), ts, ts });
		}
		int[][] counts = jdbcTemplate.batchUpdate("""
				INSERT INTO filing (company_id, accession_number, form_type, filing_date, report_date, acceptance_at,
				                    primary_document, primary_doc_description, items, size_bytes, is_xbrl,
				                    is_inline_xbrl, file_number, film_number, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (accession_number) DO NOTHING
				""", rows, 500, (ps, row) -> {
			for (int i = 0; i < row.length; i++) {
				ps.setObject(i + 1, row[i]);
			}
		});
		return Arrays.stream(counts).flatMapToInt(Arrays::stream).map(c -> Math.max(c, 0)).sum();
	}

	/** The given accession numbers that are already stored. */
	Set<String> knownAccessions(Collection<String> accessionNumbers) {
		if (accessionNumbers.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("SELECT accession_number FROM filing WHERE accession_number IN (:a)")
			.param("a", accessionNumbers)
			.query(String.class)
			.list());
	}

	List<Long> idsOf(Collection<String> accessionNumbers) {
		if (accessionNumbers.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT id FROM filing WHERE accession_number IN (:a) ORDER BY filing_date, id")
			.param("a", accessionNumbers)
			.query(Long.class)
			.list();
	}

	record SyncState(Instant recentSyncedAt, Set<String> importedHistoryFiles) {
	}

	SyncState syncState(long companyId) {
		return jdbc.sql("SELECT recent_synced_at, imported_history_files FROM company_filing_sync WHERE company_id = :id")
			.param("id", companyId)
			.query((rs, n) -> new SyncState(instant(rs, "recent_synced_at"),
					Set.of((String[]) rs.getArray("imported_history_files").getArray())))
			.optional()
			.orElse(new SyncState(null, Set.of()));
	}

	void markRecentSynced(long companyId, Instant now) {
		jdbc.sql("""
				INSERT INTO company_filing_sync (company_id, recent_synced_at) VALUES (:id, :now)
				ON CONFLICT (company_id) DO UPDATE SET recent_synced_at = EXCLUDED.recent_synced_at
				""").param("id", companyId).param("now", Timestamp.from(now)).update();
	}

	void markHistoryImported(long companyId, String file) {
		jdbc.sql("""
				INSERT INTO company_filing_sync (company_id, imported_history_files) VALUES (:id, ARRAY[:file])
				ON CONFLICT (company_id) DO UPDATE
				SET imported_history_files = array_append(company_filing_sync.imported_history_files, :file)
				WHERE NOT :file = ANY (company_filing_sync.imported_history_files)
				""").param("id", companyId).param("file", file).update();
	}

	// --- Queries ---------------------------------------------------------------

	record Cursor(LocalDate filingDate, long id) {
	}

	/** Newest first; the cursor is the last row of the previous page. */
	List<FilingSummary> page(long companyId, String cik, List<String> forms, Cursor after, int limit) {
		return jdbc.sql("""
				SELECT id, accession_number, form_type, filing_date, report_date, acceptance_at,
				       primary_doc_description, items, primary_document, status
				FROM filing
				WHERE company_id = :companyId AND form_type = ANY (CAST(:forms AS text[]))
				  AND (CAST(:afterDate AS date) IS NULL OR (filing_date, id) < (CAST(:afterDate AS date), :afterId))
				ORDER BY filing_date DESC, id DESC
				LIMIT :limit
				""")
			.param("companyId", companyId)
			.param("forms", forms.toArray(String[]::new))
			.param("afterDate", after == null ? null : Date.valueOf(after.filingDate()))
			.param("afterId", after == null ? 0L : after.id())
			.param("limit", limit)
			.query((rs, n) -> summary(rs, cik))
			.list();
	}

	Optional<Cursor> cursorOf(String accession) {
		return jdbc.sql("SELECT filing_date, id FROM filing WHERE accession_number = :a")
			.param("a", accession)
			.query((rs, n) -> new Cursor(rs.getDate("filing_date").toLocalDate(), rs.getLong("id")))
			.optional();
	}

	Optional<FilingDetail> findDetail(String accession) {
		record Row(long id, FilingDetail detail) {
		}
		return jdbc.sql("""
				SELECT f.*, c.cik, c.name AS company_name
				FROM filing f JOIN company c ON c.id = f.company_id
				WHERE f.accession_number = :a
				""").param("a", accession).query((rs, n) -> {
			String cik = rs.getString("cik");
			String primary = rs.getString("primary_document");
			return new Row(rs.getLong("id"), new FilingDetail(accession,
					new CompanyInfo(cik, rs.getString("company_name")), rs.getString("form_type"),
					rs.getDate("filing_date").toLocalDate(), date(rs, "report_date"), instant(rs, "acceptance_at"),
					rs.getString("primary_doc_description"), items(rs.getString("items")),
					rs.getObject("size_bytes", Long.class), rs.getBoolean("is_xbrl"), rs.getBoolean("is_inline_xbrl"),
					rs.getString("file_number"), Status.valueOf(rs.getString("status")), rs.getString("status_detail"),
					instant(rs, "fetched_at"), EdgarUrls.filingIndexUrl(cik, accession),
					primary == null ? null : EdgarUrls.documentUrl(cik, accession, primary), List.of()));
		}).optional().map(row -> row.detail().withDocuments(documents(row.id())));
	}

	private List<FilingDocumentView> documents(long filingId) {
		return jdbc.sql("""
				SELECT sequence, filename, doc_type, description, size_bytes, is_primary, source_url, blob_key, sha256
				FROM filing_document WHERE filing_id = :id ORDER BY sequence, filename
				""")
			.param("id", filingId)
			.query((rs, n) -> new FilingDocumentView(rs.getInt("sequence"), rs.getString("filename"),
					rs.getString("doc_type"), rs.getString("description"), rs.getObject("size_bytes", Long.class),
					rs.getBoolean("is_primary"), rs.getString("source_url"), rs.getString("blob_key") != null,
					rs.getString("sha256")))
			.list();
	}

	// --- Fetching --------------------------------------------------------------

	record FetchTarget(long id, String accession, String cik, String formType, String primaryDocument,
			String primaryDescription, Status status) {
	}

	Optional<FetchTarget> findFetchTarget(String accession) {
		return jdbc.sql("""
				SELECT f.id, f.accession_number, c.cik, f.form_type, f.primary_document, f.primary_doc_description,
				       f.status
				FROM filing f JOIN company c ON c.id = f.company_id
				WHERE f.accession_number = :a
				""")
			.param("a", accession)
			.query((rs, n) -> new FetchTarget(rs.getLong("id"), rs.getString("accession_number"), rs.getString("cik"),
					rs.getString("form_type"), rs.getString("primary_document"),
					rs.getString("primary_doc_description"), Status.valueOf(rs.getString("status"))))
			.optional();
	}

	/** Moves a filing to FETCHING unless it is already fetching or fetched. */
	boolean markFetching(String accession, Instant now) {
		return jdbc.sql("""
				UPDATE filing SET status = 'FETCHING', status_detail = NULL, updated_at = :now
				WHERE accession_number = :a AND status IN ('DISCOVERED', 'FAILED')
				""").param("a", accession).param("now", Timestamp.from(now)).update() == 1;
	}

	void markFetched(long filingId, Instant now) {
		jdbc.sql("""
				UPDATE filing SET status = 'FETCHED', status_detail = NULL, fetched_at = :now, updated_at = :now
				WHERE id = :id
				""").param("id", filingId).param("now", Timestamp.from(now)).update();
	}

	void markFailed(String accession, String detail, Instant now) {
		jdbc.sql("""
				UPDATE filing SET status = 'FAILED', status_detail = :detail, updated_at = :now
				WHERE accession_number = :a
				""").param("a", accession).param("detail", truncate(detail, 1000)).param("now", Timestamp.from(now)).update();
	}

	void upsertDocuments(FetchTarget filing, List<FilingIndexDocument> documents, String primaryFilename) {
		for (FilingIndexDocument doc : documents) {
			jdbc.sql("""
					INSERT INTO filing_document (filing_id, sequence, filename, doc_type, description, size_bytes,
					                             is_primary, source_url)
					VALUES (:filingId, :sequence, :filename, :type, :description, :size, :primary, :url)
					ON CONFLICT (filing_id, filename) DO UPDATE SET
					    sequence = EXCLUDED.sequence, doc_type = EXCLUDED.doc_type, description = EXCLUDED.description,
					    size_bytes = EXCLUDED.size_bytes, is_primary = EXCLUDED.is_primary
					""")
				.param("filingId", filing.id())
				.param("sequence", doc.sequence())
				.param("filename", doc.filename())
				.param("type", truncate(doc.type(), 50))
				.param("description", truncate(doc.description(), 500))
				.param("size", doc.sizeBytes())
				.param("primary", doc.filename().equals(primaryFilename))
				.param("url", EdgarUrls.documentUrl(filing.cik(), filing.accession(), doc.filename()))
				.update();
		}
	}

	void recordStoredDocument(long filingId, String filename, StoredBlob blob, String contentType, Instant now) {
		jdbc.sql("""
				UPDATE filing_document SET blob_key = :key, sha256 = :sha, content_type = :contentType,
				       size_bytes = COALESCE(size_bytes, :size), fetched_at = :now
				WHERE filing_id = :filingId AND filename = :filename
				""")
			.param("key", blob.key())
			.param("sha", blob.sha256())
			.param("contentType", contentType)
			.param("size", blob.sizeBytes())
			.param("now", Timestamp.from(now))
			.param("filingId", filingId)
			.param("filename", filename)
			.update();
	}

	// --- Mapping ---------------------------------------------------------------

	private static FilingSummary summary(ResultSet rs, String cik) throws SQLException {
		String accession = rs.getString("accession_number");
		String primary = rs.getString("primary_document");
		return new FilingSummary(accession, rs.getString("form_type"), rs.getDate("filing_date").toLocalDate(),
				date(rs, "report_date"), instant(rs, "acceptance_at"), rs.getString("primary_doc_description"),
				items(rs.getString("items")), primary == null ? null : EdgarUrls.documentUrl(cik, accession, primary),
				Status.valueOf(rs.getString("status")));
	}

	static List<String> items(String items) {
		if (items == null || items.isBlank()) {
			return List.of();
		}
		return Arrays.stream(items.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
	}

	private static LocalDate date(ResultSet rs, String column) throws SQLException {
		Date date = rs.getDate(column);
		return date == null ? null : date.toLocalDate();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
