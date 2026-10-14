package com.filinglens.edgar.internal;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.filinglens.edgar.Accession;
import com.filinglens.edgar.Cik;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.CompanyTickerEntry;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.edgar.EdgarUrls;
import com.filinglens.edgar.FilingIndexDocument;
import com.filinglens.edgar.FilingRecord;
import com.filinglens.platform.config.FilingLensProperties;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class HttpEdgarClient implements EdgarClient {

	private static final Pattern HISTORY_FILE = Pattern.compile("CIK\\d{10}-submissions-\\d{3}\\.json");

	private final EdgarTransport transport;

	private final JsonMapper json;

	private final URI www;

	private final URI data;

	@Autowired
	HttpEdgarClient(FilingLensProperties properties, JsonMapper json, MeterRegistry meters) {
		this(new EdgarTransport(properties.sec(), new RequestPacer(properties.sec().maxRequestsPerSecond()),
				Duration.ofSeconds(1), duration -> Thread.sleep(duration), meters), json, properties.sec());
	}

	HttpEdgarClient(EdgarTransport transport, JsonMapper json, FilingLensProperties.Sec settings) {
		this.transport = transport;
		this.json = json;
		this.www = settings.wwwBaseUrl();
		this.data = settings.dataBaseUrl();
	}

	@Override
	public List<CompanyTickerEntry> fetchCompanyTickers() {
		byte[] body = transport.get(www.resolve("/files/company_tickers_exchange.json"))
			.orElseThrow(() -> new EdgarUnavailableException("SEC ticker directory not found"));
		return parseCompanyTickers(body);
	}

	@Override
	public Optional<CompanySubmissions> fetchSubmissions(String cik) {
		String normalized = Cik.normalize(cik);
		return transport.get(data.resolve("/submissions/CIK" + normalized + ".json")).map(body -> {
			JsonNode root = parse(body);
			List<String> historyFiles = new ArrayList<>();
			for (JsonNode file : root.path("filings").path("files")) {
				String name = file.path("name").asString("");
				if (HISTORY_FILE.matcher(name).matches()) {
					historyFiles.add(name);
				}
			}
			return new CompanySubmissions(normalized, text(root, "name"), text(root, "entityType"), text(root, "sic"),
					text(root, "sicDescription"), text(root, "category"), text(root, "fiscalYearEnd"),
					text(root, "stateOfIncorporation"), strings(root.path("tickers")), strings(root.path("exchanges")),
					parseFilingColumns(root.path("filings").path("recent")), historyFiles);
		});
	}

	@Override
	public List<FilingRecord> fetchSubmissionsHistory(String fileName) {
		if (fileName == null || !HISTORY_FILE.matcher(fileName).matches()) {
			throw new IllegalArgumentException("Invalid submissions history file: " + fileName);
		}
		return transport.get(data.resolve("/submissions/" + fileName))
			.map(body -> parseFilingColumns(parse(body)))
			.orElse(List.of());
	}

	@Override
	public Optional<List<FilingIndexDocument>> fetchFilingIndex(String cik, String accession) {
		return transport.get(www.resolve(EdgarUrls.filingIndexPath(cik, accession)))
			.map(body -> parseFilingIndex(new String(body, StandardCharsets.UTF_8)));
	}

	@Override
	public Optional<byte[]> fetchArchiveDocument(String cik, String accession, String filename) {
		return transport.get(www.resolve(EdgarUrls.documentPath(cik, accession, filename)));
	}

	/**
	 * Parses SEC's columnar format:
	 * {@code {"fields":["cik","name","ticker","exchange"],"data":[[320193,"Apple Inc.","AAPL","Nasdaq"],...]}}.
	 */
	List<CompanyTickerEntry> parseCompanyTickers(byte[] body) {
		JsonNode root = parse(body);
		List<String> fields = new ArrayList<>();
		root.path("fields").forEach(field -> fields.add(field.asString()));
		int cik = fields.indexOf("cik");
		int name = fields.indexOf("name");
		int ticker = fields.indexOf("ticker");
		int exchange = fields.indexOf("exchange");
		if (cik < 0 || name < 0 || ticker < 0) {
			throw new EdgarUnavailableException("SEC ticker directory has an unexpected format");
		}
		List<CompanyTickerEntry> entries = new ArrayList<>();
		for (JsonNode row : root.path("data")) {
			JsonNode cikNode = row.path(cik);
			String tickerValue = row.path(ticker).asString("");
			if (!cikNode.canConvertToLong() || tickerValue.isBlank()) {
				continue;
			}
			String exchangeValue = exchange >= 0 && !row.path(exchange).isNull() ? row.path(exchange).asString("")
					: "";
			entries.add(new CompanyTickerEntry(Cik.normalize(cikNode.asLong()), row.path(name).asString("").strip(),
					tickerValue.strip().toUpperCase(), exchangeValue.isBlank() ? null : exchangeValue));
		}
		return entries;
	}

	/**
	 * Parses the submissions API's column-oriented filing table: parallel arrays
	 * keyed by field ({@code accessionNumber}, {@code form}, {@code filingDate}, ...).
	 * Rows with an invalid accession number or filing date are skipped.
	 */
	static List<FilingRecord> parseFilingColumns(JsonNode table) {
		JsonNode accessions = table.path("accessionNumber");
		List<FilingRecord> filings = new ArrayList<>(accessions.size());
		for (int i = 0; i < accessions.size(); i++) {
			String accession = accessions.path(i).asString("");
			LocalDate filingDate = date(cell(table, "filingDate", i));
			if (!Accession.isValid(accession) || filingDate == null) {
				continue;
			}
			JsonNode size = table.path("size").path(i);
			filings.add(new FilingRecord(Accession.normalize(accession), cell(table, "form", i), filingDate,
					date(cell(table, "reportDate", i)), instant(cell(table, "acceptanceDateTime", i)),
					cell(table, "primaryDocument", i), cell(table, "primaryDocDescription", i), cell(table, "items", i),
					size.canConvertToLong() ? size.asLong() : null, table.path("isXBRL").path(i).asInt(0) == 1,
					table.path("isInlineXBRL").path(i).asInt(0) == 1, cell(table, "fileNumber", i),
					cell(table, "filmNumber", i)));
		}
		return filings;
	}

	/**
	 * Parses the document tables ({@code table.tableFile}) of a filing index page.
	 * Each row has Seq, Description, Document, Type and Size cells. Rows without a
	 * sequence number (the complete submission text file) and rows with unsafe file
	 * names are skipped.
	 */
	static List<FilingIndexDocument> parseFilingIndex(String html) {
		Document page = Jsoup.parse(html);
		List<FilingIndexDocument> documents = new ArrayList<>();
		for (Element row : page.select("table.tableFile tr")) {
			Elements cells = row.select("td");
			if (cells.size() < 5) {
				continue;
			}
			Integer sequence = integer(cells.get(0).text());
			Element link = cells.get(2).selectFirst("a");
			String filename = link != null ? link.text().strip() : cells.get(2).text().strip();
			if (sequence == null || !EdgarUrls.isValidFilename(filename)) {
				continue;
			}
			Integer size = integer(cells.get(4).text());
			documents.add(new FilingIndexDocument(sequence, blankToNull(cells.get(1).text()), filename,
					blankToNull(cells.get(3).text()), size == null ? null : size.longValue()));
		}
		return documents;
	}

	private JsonNode parse(byte[] body) {
		try {
			return json.readTree(body);
		}
		catch (JacksonException ex) {
			throw new EdgarUnavailableException("SEC EDGAR returned malformed JSON");
		}
	}

	private static String cell(JsonNode table, String field, int index) {
		JsonNode node = table.path(field).path(index);
		return node.isMissingNode() || node.isNull() ? null : blankToNull(node.asString(""));
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : blankToNull(value.asString(""));
	}

	private static List<String> strings(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(v -> {
			if (!v.isNull()) {
				values.add(v.asString(""));
			}
		});
		return values;
	}

	private static LocalDate date(String value) {
		try {
			return value == null ? null : LocalDate.parse(value);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static Instant instant(String value) {
		try {
			return value == null ? null : Instant.parse(value);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static Integer integer(String value) {
		String digits = value == null ? "" : value.replace(" ", "").strip();
		return digits.matches("\\d{1,9}") ? Integer.valueOf(digits) : null;
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.replace(" ", " ").strip();
		return stripped.isEmpty() ? null : stripped;
	}

}
