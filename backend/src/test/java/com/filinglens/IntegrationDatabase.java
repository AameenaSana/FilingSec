package com.filinglens;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Where integration tests get PostgreSQL: an existing server when
 * {@code TEST_DB_URL} is set (for example
 * {@code jdbc:postgresql://localhost:5432/filinglens_test}, with the {@code vector},
 * {@code pg_trgm} and {@code citext} extensions available), otherwise a
 * Testcontainers container. Tests are skipped, not failed, when neither is
 * available. The tests delete data, so never point {@code TEST_DB_URL} at a
 * database you want to keep.
 */
public final class IntegrationDatabase implements ExecutionCondition {

	static final String URL = "TEST_DB_URL";

	static boolean external() {
		String url = System.getenv(URL);
		return url != null && !url.isBlank();
	}

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		if (external()) {
			return ConditionEvaluationResult.enabled("Using the database at " + URL);
		}
		return DockerClientFactory.instance().isDockerAvailable()
				? ConditionEvaluationResult.enabled("Docker is available for Testcontainers")
				: ConditionEvaluationResult.disabled("Neither " + URL + " nor Docker is available");
	}

}
