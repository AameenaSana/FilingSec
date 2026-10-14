package com.filinglens.edgar.internal;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.filinglens.edgar.Cik;
import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.CompanyTickerEntry;
import com.filinglens.edgar.EdgarClient;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.platform.config.FilingLensProperties;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class HttpEdgarClient implements EdgarClient {

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
			SubmissionsJson parsed = parse(body, SubmissionsJson.class);
			return new CompanySubmissions(normalized, parsed.name(), parsed.entityType(), blankToNull(parsed.sic()),
					blankToNull(parsed.sicDescription()), blankToNull(parsed.category()),
					blankToNull(parsed.fiscalYearEnd()), blankToNull(parsed.stateOfIncorporation()), parsed.tickers(),
					parsed.exchanges());
		});
	}

	/**
	 * Parses SEC's columnar format:
	 * {@code {"fields":["cik","name","ticker","exchange"],"data":[[320193,"Apple Inc.","AAPL","Nasdaq"],...]}}.
	 */
	List<CompanyTickerEntry> parseCompanyTickers(byte[] body) {
		JsonNode root = parse(body, JsonNode.class);
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

	private <T> T parse(byte[] body, Class<T> type) {
		try {
			return json.readValue(body, type);
		}
		catch (JacksonException ex) {
			throw new EdgarUnavailableException("SEC EDGAR returned malformed JSON");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SubmissionsJson(String name, String entityType, String sic, String sicDescription, String category,
			String fiscalYearEnd, String stateOfIncorporation, List<String> tickers, List<String> exchanges) {
	}

}
