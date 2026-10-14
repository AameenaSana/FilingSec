/**
 * Shared platform infrastructure: configuration, error handling, request correlation,
 * the durable job queue, and blob storage. Contains no business logic.
 * <p>
 * Declared as an open module so every business module may use its subpackages.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.filinglens.platform;

import org.springframework.modulith.ApplicationModule;
