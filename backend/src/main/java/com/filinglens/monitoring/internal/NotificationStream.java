package com.filinglens.monitoring.internal;

import java.io.IOException;
import java.time.Duration;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.filinglens.monitoring.internal.NotificationViews.NotificationView;

/**
 * Open server-sent event connections per user. Events reach connections held by
 * this application instance; with several instances, clients on the others see
 * new notifications when they next fetch the list.
 */
@Component
class NotificationStream {

	static final Duration TIMEOUT = Duration.ofMinutes(30);

	static final int MAX_CONNECTIONS_PER_USER = 5;

	private final Map<UUID, Deque<SseEmitter>> connections = new ConcurrentHashMap<>();

	record UnreadEvent(int unreadCount) {
	}

	record NotificationEvent(NotificationView notification, int unreadCount) {
	}

	SseEmitter open(UUID userId, int unreadCount) {
		SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
		Deque<SseEmitter> userConnections = connections.computeIfAbsent(userId, id -> new ConcurrentLinkedDeque<>());
		userConnections.add(emitter);
		while (userConnections.size() > MAX_CONNECTIONS_PER_USER) {
			SseEmitter oldest = userConnections.poll();
			if (oldest != null) {
				oldest.complete();
			}
		}
		Runnable remove = () -> remove(userId, emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(error -> remove.run());
		send(userId, emitter, SseEmitter.event().name("unread").data(new UnreadEvent(unreadCount)));
		return emitter;
	}

	void publish(UUID userId, NotificationView notification, int unreadCount) {
		Deque<SseEmitter> userConnections = connections.get(userId);
		if (userConnections == null) {
			return;
		}
		for (SseEmitter emitter : userConnections) {
			send(userId, emitter,
					SseEmitter.event().name("notification").data(new NotificationEvent(notification, unreadCount)));
		}
	}

	void publishUnread(UUID userId, int unreadCount) {
		Deque<SseEmitter> userConnections = connections.get(userId);
		if (userConnections == null) {
			return;
		}
		for (SseEmitter emitter : userConnections) {
			send(userId, emitter, SseEmitter.event().name("unread").data(new UnreadEvent(unreadCount)));
		}
	}

	/** Keeps idle connections open through proxies that close silent streams. */
	@Scheduled(fixedDelay = 25_000)
	void heartbeat() {
		connections.forEach((userId, userConnections) -> userConnections
			.forEach(emitter -> send(userId, emitter, SseEmitter.event().comment("keep-alive"))));
	}

	int connectionCount(UUID userId) {
		Deque<SseEmitter> userConnections = connections.get(userId);
		return userConnections == null ? 0 : userConnections.size();
	}

	private void send(UUID userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
		try {
			emitter.send(event);
		}
		catch (IOException | IllegalStateException ex) {
			// The client went away.
			remove(userId, emitter);
		}
	}

	private void remove(UUID userId, SseEmitter emitter) {
		connections.computeIfPresent(userId, (id, userConnections) -> {
			userConnections.remove(emitter);
			return userConnections.isEmpty() ? null : userConnections;
		});
	}

}
