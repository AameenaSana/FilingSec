package com.filinglens;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fails the build if any module reaches into another module's internals or if
 * modules form a dependency cycle.
 */
class ModularityTests {

	private final ApplicationModules modules = ApplicationModules.of(FilingLensApplication.class);

	@Test
	void verifiesModuleBoundaries() {
		modules.verify();
	}

}
