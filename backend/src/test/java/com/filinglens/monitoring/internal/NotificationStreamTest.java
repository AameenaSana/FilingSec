package com.filinglens.monitoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.filinglens.monitoring.internal.NotificationViews.NotificationView;
import com.filinglens.monitoring.internal.NotificationViews.Type;

class NotificationStreamTest {

	final NotificationStream stream = new NotificationStream();

	@Test
	void limitsConnectionsPerUserAndForgetsClosedOnes() {
		UUID user = UUID.randomUUID();
		for (int i = 0; i < NotificationStream.MAX_CONNECTIONS_PER_USER + 2; i++) {
			stream.open(user, 0);
		}
		assertThat(stream.connectionCount(user)).isEqualTo(NotificationStream.MAX_CONNECTIONS_PER_USER);

		// Without a servlet response the emitters cannot deliver, so publishing drops them.
		stream.publish(user, new NotificationView(1, Type.NEW_FILING, "t", "b", "/l", Instant.now(), false), 1);
		stream.heartbeat();
		assertThat(stream.connectionCount(user)).isLessThanOrEqualTo(NotificationStream.MAX_CONNECTIONS_PER_USER);
		assertThat(stream.connectionCount(UUID.randomUUID())).isZero();
	}

}
