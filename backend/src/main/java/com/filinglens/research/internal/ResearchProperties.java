package com.filinglens.research.internal;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI configuration. Secrets come only from the environment. Without an LLM key
 * the Q&A endpoints report that AI is not configured; without an embedding key
 * retrieval uses full-text search only.
 */
@Validated
@ConfigurationProperties(prefix = "filinglens.research")
record ResearchProperties(@Valid @NotNull Llm llm, @Valid @NotNull Embeddings embeddings,
		@Min(1) long dailyTokenLimitPerUser, @Min(1) int passagesPerAnswer) {

	/**
	 * @param effort Claude effort level: low, medium, high, xhigh or max
	 */
	record Llm(String apiKey, @NotBlank String model, @Min(1024) long maxOutputTokens, @NotBlank String effort) {

		boolean configured() {
			return apiKey != null && !apiKey.isBlank();
		}

	}

	/**
	 * @param dimensions must match the {@code vector(1024)} column
	 */
	record Embeddings(String apiKey, @NotNull URI baseUrl, @NotBlank String model, @Min(1) int dimensions,
			@Min(1) int batchSize) {

		boolean configured() {
			return apiKey != null && !apiKey.isBlank();
		}

	}

}
