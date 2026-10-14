/**
 * Monitoring: polls SEC for new filings of watched companies, prepares and
 * compares new periodic reports automatically, and notifies watchers in the app
 * (live over server-sent events) and by email.
 * <p>
 * No public API: it reacts to {@link com.filinglens.filing.FilingsDiscovered} and
 * {@link com.filinglens.comparison.ComparisonCompleted}.
 */
@ApplicationModule(displayName = "Monitoring")
package com.filinglens.monitoring;

import org.springframework.modulith.ApplicationModule;
