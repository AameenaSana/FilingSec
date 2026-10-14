package com.filinglens.monitoring.internal;

import java.time.Instant;
import java.util.List;

/** API representations of notifications. */
final class NotificationViews {

	private NotificationViews() {
	}

	enum Type {

		NEW_FILING, COMPARISON_READY

	}

	/** @param link path within the app */
	record NotificationView(long id, Type type, String title, String body, String link, Instant createdAt,
			boolean read) {
	}

	record NotificationPage(int unreadCount, List<NotificationView> items) {
	}

	record Preferences(boolean emailEnabled) {
	}

}
