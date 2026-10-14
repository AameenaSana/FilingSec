package com.filinglens.research.internal;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Retrieval configuration. Without an embedding key retrieval uses full-text
 * search only. The LLM is configured in the AI module.
 */
@Validated
@ConfigurationProperties(prefix = "filinglens.research")
record ResearchProperties(@Valid @NotNull Embeddings embeddings, @Min(1) int passagesPerAnswer) {

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
