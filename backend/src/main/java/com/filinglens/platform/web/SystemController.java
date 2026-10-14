package com.filinglens.platform.web;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated service information used by the frontend to confirm
 * connectivity. Exposes no configuration or environment details.
 */
@RestController
@RequestMapping("/api/v1/system")
class SystemController {

	private final String version;

	private final Clock clock;

	SystemController(ObjectProvider<BuildProperties> buildProperties, Clock clock) {
		BuildProperties build = buildProperties.getIfAvailable();
		this.version = build != null ? build.getVersion() : "dev";
		this.clock = clock;
	}

	@GetMapping("/info")
	SystemInfo info() {
		return new SystemInfo("FilingLens", version, Instant.now(clock));
	}

	record SystemInfo(String name, String version, Instant serverTime) {
	}

}
