package com.filinglens.ai.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.ai.AiUsage;
import com.filinglens.ai.QuotaExceededException;

@Component
class JdbcAiUsage implements AiUsage {

	private final JdbcClient jdbc;

	private final AiProperties properties;

	private final Clock clock;

	JdbcAiUsage(JdbcClient jdbc, AiProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void checkQuota(UUID userId) {
		Instant startOfDay = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
		if (tokensUsedSince(userId, startOfDay) >= properties.dailyTokenLimitPerUser()) {
			throw new QuotaExceededException();
		}
	}

	@Override
	public void record(UUID userId, String purpose, AiResponse response) {
		jdbc.sql("""
				INSERT INTO llm_usage (user_id, purpose, model, input_tokens, output_tokens, created_at)
				VALUES (:user, :purpose, :model, :input, :output, :now)
				""")
			.param("user", userId)
			.param("purpose", purpose)
			.param("model", response.model())
			.param("input", (int) response.inputTokens())
			.param("output", (int) response.outputTokens())
			.param("now", Timestamp.from(clock.instant()))
			.update();
	}

	long tokensUsedSince(UUID userId, Instant since) {
		return jdbc.sql("""
				SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM llm_usage
				WHERE user_id = :user AND created_at >= :since
				""").param("user", userId).param("since", Timestamp.from(since)).query(Long.class).single();
	}

}
