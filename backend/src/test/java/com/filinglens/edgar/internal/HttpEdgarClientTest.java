package com.filinglens.edgar.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.filinglens.edgar.CompanySubmissions;
import com.filinglens.edgar.CompanyTickerEntry;
import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.edgar.FilingIndexDocument;
import com.filinglens.edgar.FilingRecord;
import com.filinglens.edgar.internal.StubSecServer.Response;
import com.filinglens.platform.config.FilingLensProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

class HttpEdgarClientTest {

	static final String USER_AGENT = "FilingLens test test@example.com";

	StubSecServer sec;

	List<Duration> sleeps;

	SimpleMeterRegistry meters;

	HttpEdgarClient client;

	@BeforeEach
	void setUp() throws IOException {
		sec = new StubSecServer();
		sleeps = new ArrayList<>();
		meters = new SimpleMeterRegistry();
		FilingLensProperties.Sec settings = new FilingLensProperties.Sec(USER_AGENT, sec.baseUri(), sec.baseUri(), 10,
				Duration.ofSeconds(5), Duration.ofMinutes(10));
		EdgarTransport transport = new EdgarTransport(settings, new RequestPacer(10), Duration.ofMillis(100),
				sleeps::add, meters);
		client = new HttpEdgarClient(transport, JsonMapper.builder().build(), settings);
	}

	@AfterEach
	void tearDown() {
		sec.close();
	}

	@Test
	void parsesTickerDirectoryAndSendsDeclaredUserAgentWithGzip() throws IOException {
		sec.on("/files/company_tickers_exchange.json", Response.ok(fixture("company_tickers_exchange.json")));

		List<CompanyTickerEntry> entries = client.fetchCompanyTickers();

		assertThat(entries).hasSize(6)
			.first()
			.isEqualTo(new CompanyTickerEntry("0000320193", "Apple Inc.", "AAPL", "Nasdaq"));
		assertThat(entries).extracting(CompanyTickerEntry::ticker).containsSubsequence("GOOGL", "GOOG");
		assertThat(entries).filteredOn(e -> e.ticker().equals("EXPS")).first().extracting(CompanyTickerEntry::exchange)
			.isNull();
		var request = sec.requests().getFirst();
		assertThat(request.headers().getFirst("User-Agent")).isEqualTo(USER_AGENT);
		assertThat(request.headers().getFirst("Accept-Encoding")).contains("gzip");
	}

	@Test
	void parsesSubmissionsProfileWithZeroPaddedCik() throws IOException {
		sec.on("/submissions/CIK0000320193.json", Response.ok(fixture("CIK0000320193.json")));

		CompanySubmissions profile = client.fetchSubmissions("320193").orElseThrow();

		assertThat(profile.cik()).isEqualTo("0000320193");
		assertThat(profile.name()).isEqualTo("Apple Inc.");
		assertThat(profile.sic()).isEqualTo("3571");
		assertThat(profile.sicDescription()).isEqualTo("Electronic Computers");
		assertThat(profile.fiscalYearEnd()).isEqualTo("0927");
		assertThat(profile.stateOfIncorporation()).isEqualTo("CA");
		assertThat(profile.tickers()).containsExactly("AAPL");
		assertThat(profile.exchanges()).containsExactly("Nasdaq");
	}

	@Test
	void parsesRecentFilingsSkippingInvalidRowsAndUnsafeHistoryFileNames() throws IOException {
		sec.on("/submissions/CIK0000320193.json", Response.ok(fixture("CIK0000320193.json")));

		CompanySubmissions submissions = client.fetchSubmissions("320193").orElseThrow();

		assertThat(submissions.recentFilings()).hasSize(5).first().satisfies(f -> {
			assertThat(f.accessionNumber()).isEqualTo("0000320193-25-000079");
			assertThat(f.form()).isEqualTo("10-K");
			assertThat(f.filingDate()).isEqualTo(LocalDate.of(2025, 10, 31));
			assertThat(f.reportDate()).isEqualTo(LocalDate.of(2025, 9, 27));
			assertThat(f.acceptanceDateTime()).isEqualTo(Instant.parse("2025-10-31T06:01:26Z"));
			assertThat(f.primaryDocument()).isEqualTo("aapl-20250927.htm");
			assertThat(f.items()).isNull();
			assertThat(f.inlineXbrl()).isTrue();
			assertThat(f.size()).isEqualTo(9374416L);
		});
		assertThat(submissions.recentFilings()).filteredOn(f -> f.form().equals("8-K"))
			.singleElement()
			.extracting(FilingRecord::items)
			.isEqualTo("2.02,9.01");
		assertThat(submissions.historyFiles()).containsExactly("CIK0000320193-submissions-001.json");
	}

	@Test
	void parsesHistoryPage() throws IOException {
		sec.on("/submissions/CIK0000320193-submissions-001.json",
				Response.ok(fixture("CIK0000320193-submissions-001.json")));

		List<FilingRecord> filings = client.fetchSubmissionsHistory("CIK0000320193-submissions-001.json");

		assertThat(filings).extracting(FilingRecord::accessionNumber)
			.containsExactly("0001193125-14-383437", "0001193125-14-277160");
		assertThatThrownBy(() -> client.fetchSubmissionsHistory("../secret.json"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void parsesFilingIndexDocumentsSkippingUnsafeAndUnnumberedRows() throws IOException {
		sec.on("/Archives/edgar/data/320193/000032019325000079/0000320193-25-000079-index.htm",
				Response.ok(fixture("0000320193-25-000079-index.htm")));

		List<FilingIndexDocument> documents = client.fetchFilingIndex("320193", "0000320193-25-000079").orElseThrow();

		assertThat(documents).containsExactly(
				new FilingIndexDocument(1, "10-K", "aapl-20250927.htm", "10-K", 1485736L),
				new FilingIndexDocument(2, "EX-21.1", "a10-kexhibit21109272025.htm", "EX-21.1", 8243L),
				new FilingIndexDocument(7, "XBRL TAXONOMY EXTENSION SCHEMA DOCUMENT", "aapl-20250927.xsd", "EX-101.SCH",
						null));
	}

	@Test
	void fetchesArchiveDocumentBytes() {
		byte[] html = "<html><body>Annual report</body></html>".getBytes();
		sec.on("/Archives/edgar/data/320193/000032019325000079/aapl-20250927.htm", Response.ok(html));

		assertThat(client.fetchArchiveDocument("0000320193", "000032019325000079", "aapl-20250927.htm"))
			.hasValue(html);
		assertThat(client.fetchArchiveDocument("0000320193", "0000320193-25-000079", "missing.htm")).isEmpty();
		assertThatThrownBy(() -> client.fetchArchiveDocument("320193", "0000320193-25-000079", "../x.htm"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unknownCompanyIsEmpty() {
		assertThat(client.fetchSubmissions("1")).isEmpty();
	}

	@Test
	void retriesThrottlingHonoringRetryAfter() throws IOException {
		sec.on("/submissions/CIK0000320193.json", new Response(429, new byte[0], Map.of("Retry-After", "3"), false),
				Response.status(503), Response.ok(fixture("CIK0000320193.json")));

		assertThat(client.fetchSubmissions("320193")).isPresent();

		assertThat(sec.requests()).hasSize(3);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(3), Duration.ofMillis(200));
		assertThat(meters.counter("filinglens.edgar.requests", "outcome", "throttled").count()).isEqualTo(1);
	}

	@Test
	void givesUpAfterBoundedRetries() {
		sec.on("/submissions/CIK0000320193.json", Response.status(500));

		assertThatThrownBy(() -> client.fetchSubmissions("320193")).isInstanceOf(EdgarUnavailableException.class);

		assertThat(sec.requests()).hasSize(EdgarTransport.MAX_ATTEMPTS);
		assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));
	}

	@Test
	void forbiddenPausesAllTrafficWithoutFurtherRequests() {
		sec.on("/submissions/CIK0000320193.json", Response.status(403));

		assertThatThrownBy(() -> client.fetchSubmissions("320193")).isInstanceOf(EdgarUnavailableException.class);
		assertThatThrownBy(() -> client.fetchCompanyTickers()).isInstanceOf(EdgarUnavailableException.class)
			.hasMessageContaining("paused");

		assertThat(sec.requests()).hasSize(1);
	}

	@Test
	void malformedJsonIsReportedAsUnavailable() {
		sec.on("/submissions/CIK0000320193.json", Response.ok("<html>maintenance</html>".getBytes()));

		assertThatThrownBy(() -> client.fetchSubmissions("320193")).isInstanceOf(EdgarUnavailableException.class)
			.hasMessageContaining("malformed");
	}

	static byte[] fixture(String name) throws IOException {
		try (InputStream in = HttpEdgarClientTest.class.getResourceAsStream("/edgar/" + name)) {
			return in.readAllBytes();
		}
	}

}
