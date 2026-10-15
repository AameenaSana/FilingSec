package com.filinglens;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * Same image as docker-compose, so pgvector is available in tests. Not created
	 * when {@code TEST_DB_URL} names an existing server.
	 */
	@Bean
	@ServiceConnection
	@ConditionalOnExpression("'${TEST_DB_URL:}' == ''")
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
	}

}
