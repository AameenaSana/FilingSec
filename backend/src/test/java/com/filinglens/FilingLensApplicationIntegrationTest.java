package com.filinglens;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class FilingLensApplicationIntegrationTest {

	@Autowired
	JdbcClient jdbc;

	@Test
	void contextLoadsAndMigrationsApply() {
		assertThat(jdbc.sql("SELECT extname FROM pg_extension").query(String.class).list())
			.contains("vector", "citext", "pg_trgm");
		assertThat(jdbc.sql("SELECT count(*) FROM job").query(Long.class).single()).isNotNull();
	}

}
