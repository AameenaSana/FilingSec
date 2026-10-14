package com.filinglens.monitoring.internal;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.filinglens.identity.UserPrincipal;
import com.filinglens.monitoring.internal.NotificationViews.NotificationPage;
import com.filinglens.monitoring.internal.NotificationViews.Preferences;

@RestController
@Validated
class NotificationController {

	private final NotificationService notifications;

	NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	record PreferencesRequest(@NotNull Boolean emailEnabled) {
	}

	@GetMapping("/api/v1/notifications")
	NotificationPage list(@AuthenticationPrincipal UserPrincipal user,
			@RequestParam(defaultValue = "false") boolean unreadOnly,
			@RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
		return notifications.list(user.id(), unreadOnly, limit);
	}

	@PostMapping("/api/v1/notifications/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void markRead(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		notifications.markRead(user.id(), id);
	}

	@PostMapping("/api/v1/notifications/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void markAllRead(@AuthenticationPrincipal UserPrincipal user) {
		notifications.markAllRead(user.id());
	}

	/**
	 * Live notifications: an {@code unread} event with the unread count on connect
	 * and on changes, and a {@code notification} event for each new notification.
	 */
	@GetMapping(value = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter stream(@AuthenticationPrincipal UserPrincipal user, HttpServletResponse response) {
		// Stop proxies and compression from buffering the event stream.
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setHeader("X-Accel-Buffering", "no");
		return notifications.stream(user.id());
	}

	@GetMapping("/api/v1/notification-preferences")
	Preferences preferences(@AuthenticationPrincipal UserPrincipal user) {
		return notifications.preferences(user.id());
	}

	@PutMapping("/api/v1/notification-preferences")
	Preferences updatePreferences(@AuthenticationPrincipal UserPrincipal user,
			@Valid @RequestBody PreferencesRequest body) {
		return notifications.updatePreferences(user.id(), body.emailEnabled());
	}

}
