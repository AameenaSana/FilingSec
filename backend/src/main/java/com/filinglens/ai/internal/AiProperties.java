package com.filinglens.ai.internal;

import java.net.URI;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * LLM configuration. The API key comes only from the environment; without it AI
 * features report that they are not configured.
 *
 * @param effort reasoning effort: low, medium, high, xhigh or max
 * @param provider {@code anthropic} for Claude, or {@code openai-compatible} for any
 * service speaking the OpenAI Chat Completions API (Gemini, Ollama, ...) at
 * {@code baseUrl}
 * @param baseUrl the OpenAI-compatible API root, ending before {@code chat/completions};
 * unused for Claude
 */
@Validated
@ConfigurationProperties(prefix = "filinglens.ai")
record AiProperties(String apiKey, @NotBlank String model, @Min(1024) long maxOutputTokens, @NotBlank String effort,
		@Min(1) long dailyTokenLimitPerUser, @Pattern(regexp = "anthropic|openai-compatible") String provider,
		URI baseUrl) {

	boolean configured() {
		return apiKey != null && !apiKey.isBlank();
	}

}
