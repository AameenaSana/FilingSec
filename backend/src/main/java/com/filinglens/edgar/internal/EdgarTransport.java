package com.filinglens.edgar.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.filinglens.edgar.EdgarUnavailableException;
import com.filinglens.platform.config.FilingLensProperties;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * HTTP transport for sec.gov that enforces SEC's fair-access policy:
 * <ul>
 * <li>declared {@code User-Agent} on every request;</li>
 * <li>requests paced below the configured rate across all threads;</li>
 * <li>gzip transfer encoding;</li>
 * <li>bounded retries with exponential backoff for 429, 5xx and I/O errors,
 * honoring {@code Retry-After};</li>
 * <li>on 403 (SEC's response when a client exceeds its limits or is not properly
 * identified) all traffic is paused for the configured duration.</li>
 * </ul>
 */
class EdgarTransport {

	private static final Logger log = LoggerFactory.getLogger(EdgarTransport.class);

	static final int MAX_ATTEMPTS = 4;

	static final long MAX_BODY_BYTES = 100L * 1024 * 1024;

	interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;

	}

	private final HttpClient http;

	private final RequestPacer pacer;

	private final String userAgent;

	private final Duration requestTimeout;

	private final Duration throttlePause;

	private final Duration baseBackoff;

	private final Sleeper sleeper;

	private final MeterRegistry meters;

	EdgarTransport(FilingLensProperties.Sec settings, RequestPacer pacer, Duration baseBackoff, Sleeper sleeper,
			MeterRegistry meters) {
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		this.pacer = pacer;
		this.userAgent = settings.userAgent();
		this.requestTimeout = settings.requestTimeout();
		this.throttlePause = settings.throttlePause();
		this.baseBackoff = baseBackoff;
		this.sleeper = sleeper;
		this.meters = meters;
	}

	/**
	 * GETs a resource.
	 * @return the decoded body, or empty on 404
	 * @throws EdgarUnavailableException if SEC cannot be reached or refuses the request
	 */
	Optional<byte[]> get(URI uri) {
		for (int attempt = 1;; attempt++) {
			Duration paused = pacer.remainingPause();
			if (!paused.isZero()) {
				record("paused");
				throw new EdgarUnavailableException(
						"SEC EDGAR access is paused for %d more seconds after SEC throttled requests"
							.formatted(paused.toSeconds()));
			}
			Duration retryDelay;
			try {
				pacer.acquire();
				HttpResponse<InputStream> response = http.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
				int status = response.statusCode();
				if (status == 200) {
					record("ok");
					return Optional.of(decode(response));
				}
				response.body().close();
				if (status == 404) {
					record("not_found");
					return Optional.empty();
				}
				if (status == 403) {
					record("forbidden");
					pacer.pause(throttlePause);
					log.error("SEC returned 403 for {}; pausing all EDGAR traffic for {}. "
							+ "Check that SEC_USER_AGENT identifies you with a contact email.", uri, throttlePause);
					throw new EdgarUnavailableException("SEC EDGAR refused the request (rate limit or identification)");
				}
				if (status != 429 && status < 500) {
					record("unexpected_status");
					throw new EdgarUnavailableException("SEC EDGAR returned unexpected status " + status);
				}
				record(status == 429 ? "throttled" : "server_error");
				retryDelay = retryAfter(response).orElse(backoff(attempt));
				log.warn("SEC returned {} for {} (attempt {}/{})", status, uri, attempt, MAX_ATTEMPTS);
			}
			catch (IOException ex) {
				record("io_error");
				retryDelay = backoff(attempt);
				log.warn("I/O error calling SEC {} (attempt {}/{}): {}", uri, attempt, MAX_ATTEMPTS, ex.toString());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new EdgarUnavailableException("Interrupted while calling SEC EDGAR");
			}
			if (attempt >= MAX_ATTEMPTS) {
				throw new EdgarUnavailableException("SEC EDGAR is unavailable; please try again later");
			}
			try {
				sleeper.sleep(retryDelay);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new EdgarUnavailableException("Interrupted while waiting to retry SEC EDGAR");
			}
		}
	}

	private HttpRequest request(URI uri) {
		return HttpRequest.newBuilder(uri)
			.timeout(requestTimeout)
			.header("User-Agent", userAgent)
			.header("Accept-Encoding", "gzip, deflate")
			.GET()
			.build();
	}

	private Duration backoff(int attempt) {
		return baseBackoff.multipliedBy(1L << (attempt - 1));
	}

	private static Optional<Duration> retryAfter(HttpResponse<?> response) {
		return response.headers().firstValue("Retry-After").flatMap(value -> {
			try {
				return Optional.of(Duration.ofSeconds(Math.min(Long.parseLong(value.strip()), 120)));
			}
			catch (NumberFormatException ex) {
				return Optional.empty();
			}
		});
	}

	private static byte[] decode(HttpResponse<InputStream> response) throws IOException {
		String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
		try (InputStream raw = response.body();
				InputStream body = switch (encoding) {
					case "gzip" -> new GZIPInputStream(raw);
					case "deflate" -> new InflaterInputStream(raw);
					default -> raw;
				}) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int read;
			while ((read = body.read(buffer)) != -1) {
				total += read;
				if (total > MAX_BODY_BYTES) {
					throw new IOException("SEC response exceeds " + MAX_BODY_BYTES + " bytes");
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	private void record(String outcome) {
		meters.counter("filinglens.edgar.requests", "outcome", outcome).increment();
	}

}
