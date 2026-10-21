package com.filinglens.research.internal;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Voyage AI embeddings ({@code POST /v1/embeddings}). Documents and queries are
 * embedded with their respective {@code input_type}, which Voyage uses to optimize
 * retrieval. Document requests are paced by the configured request interval and
 * retry rate limiting and server errors with backoff, honoring {@code Retry-After}.
 * Query requests are not retried: a question should fall back to keyword search
 * rather than wait.
 */
@Component
class VoyageEmbeddingModel implements EmbeddingModel {

	private static final int DOCUMENT_ATTEMPTS = 5;

	private static final int QUERY_ATTEMPTS = 1;

	/** Longest wait honored from a {@code Retry-After} header. */
	private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

	private final ResearchProperties.Embeddings settings;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	/** Earliest time the next document request may be sent; shared by all callers. */
	private long nextDocumentRequestNanos = System.nanoTime();

	VoyageEmbeddingModel(ResearchProperties properties, JsonMapper json) {
		this.settings = properties.embeddings();
		this.json = json;
	}

	@Override
	public boolean available() {
		return settings.configured();
	}

	@Override
	public String modelName() {
		return settings.model();
	}

	@Override
	public List<float[]> embedDocuments(List<String> texts) {
		List<float[]> vectors = new ArrayList<>(texts.size());
		for (int from = 0; from < texts.size(); from += settings.batchSize()) {
			vectors.addAll(embed(texts.subList(from, Math.min(texts.size(), from + settings.batchSize())), "document",
					DOCUMENT_ATTEMPTS));
		}
		return vectors;
	}

	@Override
	public float[] embedQuery(String text) {
		return embed(List.of(text), "query", QUERY_ATTEMPTS).getFirst();
	}

	private List<float[]> embed(List<String> inputs, String inputType, int maxAttempts) {
		if (!available()) {
			throw new IllegalStateException("No embedding API key is configured");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("input", inputs);
		body.put("model", settings.model());
		body.put("input_type", inputType);
		body.put("output_dimension", settings.dimensions());
		HttpRequest request = HttpRequest.newBuilder(settings.baseUrl().resolve("/v1/embeddings"))
			.timeout(Duration.ofSeconds(60))
			.header("Authorization", "Bearer " + settings.apiKey())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
			.build();
		for (int attempt = 1;; attempt++) {
			if ("document".equals(inputType)) {
				awaitDocumentSlot();
			}
			Duration wait = Duration.ofSeconds(1L << attempt);
			try {
				HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					return parse(response.body(), inputs.size());
				}
				boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
				if (!retryable || attempt >= maxAttempts) {
					throw new IllegalStateException("Embedding request failed with status " + response.statusCode());
				}
				wait = retryAfter(response).orElse(wait);
			}
			catch (IOException ex) {
				if (attempt >= maxAttempts) {
					throw new IllegalStateException("Embedding request failed: " + ex.getMessage(), ex);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while embedding", ex);
			}
			sleep(wait);
		}
	}

	/** Waits until the request interval since the previous document request has passed. */
	private void awaitDocumentSlot() {
		long waitNanos;
		synchronized (this) {
			long now = System.nanoTime();
			long slot = Math.max(now, nextDocumentRequestNanos);
			nextDocumentRequestNanos = slot + settings.requestInterval().toNanos();
			waitNanos = slot - now;
		}
		if (waitNanos > 0) {
			sleep(Duration.ofNanos(waitNanos));
		}
	}

	/** The server's requested wait in seconds, capped; absent if missing or not a number of seconds. */
	static Optional<Duration> retryAfter(HttpResponse<?> response) {
		return response.headers().firstValue("Retry-After").flatMap(value -> {
			try {
				long seconds = Long.parseLong(value.strip());
				Duration wait = Duration.ofSeconds(Math.max(1, seconds));
				return Optional.of(wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait);
			}
			catch (NumberFormatException ex) {
				return Optional.empty();
			}
		});
	}

	private List<float[]> parse(String body, int expected) {
		JsonNode data = json.readTree(body).path("data");
		float[][] vectors = new float[expected][];
		for (JsonNode item : data) {
			JsonNode values = item.path("embedding");
			float[] vector = new float[values.size()];
			for (int i = 0; i < vector.length; i++) {
				vector[i] = (float) values.get(i).asDouble();
			}
			if (vector.length != settings.dimensions()) {
				throw new IllegalStateException("Expected %d-dimensional embeddings but got %d"
					.formatted(settings.dimensions(), vector.length));
			}
			vectors[item.path("index").asInt()] = vector;
		}
		for (float[] vector : vectors) {
			if (vector == null) {
				throw new IllegalStateException("Embedding response is missing vectors");
			}
		}
		return List.of(vectors);
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to retry", ex);
		}
	}

}
