/**
 * Dashboard: a read-only overview for the signed-in user of their watched
 * companies, recent filings and detected changes, notifications and research.
 * <p>
 * It owns no data and has no public API. Its queries read other modules' tables
 * directly, always scoped to the user, and never write.
 */
@ApplicationModule(displayName = "Dashboard")
package com.filinglens.dashboard;

import org.springframework.modulith.ApplicationModule;
