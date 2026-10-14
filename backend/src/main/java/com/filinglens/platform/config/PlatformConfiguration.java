package com.filinglens.platform.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

	/** Injected everywhere time is read, so tests can control it. */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
