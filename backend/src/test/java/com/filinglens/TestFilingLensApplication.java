package com.filinglens;

import org.springframework.boot.SpringApplication;

/** Runs the application locally against a throwaway Testcontainers Postgres. */
public class TestFilingLensApplication {

	public static void main(String[] args) {
		SpringApplication.from(FilingLensApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
