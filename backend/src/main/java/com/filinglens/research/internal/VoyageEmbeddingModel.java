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

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Voyage AI embeddings ({@code POST /v1/embeddings}). Documents and queries are
 * embedded with their respective {@code input_type}, which Voyage uses to optimize
 * retrieval. Retries rate limiting and server errors with backoff.
 */
@Component
class VoyageEmbeddingModel implements EmbeddingModel {

	private static final int MAX_ATTEMPTS = 4;

	private final ResearchProperties.Embeddings settings;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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
			vectors.addAll(embed(texts.subList(from, Math.min(texts.size(), from + settings.batchSize())), "document"));
		}
		return vectors;
	}

	@Override
	public float[] embedQuery(String text) {
		return embed(List.of(text), "query").getFirst();
	}

	private List<float[]> embed(List<String> inputs, String inputType) {
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
			try {
				HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					return parse(response.body(), inputs.size());
				}
				boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
				if (!retryable || attempt >= MAX_ATTEMPTS) {
					throw new IllegalStateException("Embedding request failed with status " + response.statusCode());
				}
			}
			catch (IOException ex) {
				if (attempt >= MAX_ATTEMPTS) {
					throw new IllegalStateException("Embedding request failed: " + ex.getMessage(), ex);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while embedding", ex);
			}
			sleep(Duration.ofSeconds(1L << attempt));
		}
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
