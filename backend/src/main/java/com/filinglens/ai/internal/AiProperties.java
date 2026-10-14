package com.filinglens.ai.internal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * LLM configuration. The API key comes only from the environment; without it AI
 * features report that they are not configured.
 *
 * @param effort Claude effort level: low, medium, high, xhigh or max
 */
@Validated
@ConfigurationProperties(prefix = "filinglens.ai")
record AiProperties(String apiKey, @NotBlank String model, @Min(1024) long maxOutputTokens, @NotBlank String effort,
		@Min(1) long dailyTokenLimitPerUser) {

	boolean configured() {
		return apiKey != null && !apiKey.isBlank();
	}

}
