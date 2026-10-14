package com.filinglens.edgar.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RequestPacerTest {

	final AtomicLong now = new AtomicLong(0);

	final List<Long> sleeps = new ArrayList<>();

	final RequestPacer pacer = new RequestPacer(8, now::get, nanos -> {
		sleeps.add(nanos);
		now.addAndGet(nanos);
	});

	@Test
	void spacesRequestsSoNoSecondExceedsTheRate() throws InterruptedException {
		List<Long> sendTimes = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			pacer.acquire();
			sendTimes.add(now.get());
		}
		long second = TimeUnit.SECONDS.toNanos(1);
		for (int i = 0; i < sendTimes.size(); i++) {
			long start = sendTimes.get(i);
			long inWindow = sendTimes.stream().filter(t -> t >= start && t < start + second).count();
			assertThat(inWindow).isLessThanOrEqualTo(8);
		}
	}

	@Test
	void pauseDelaysAllRequests() throws InterruptedException {
		pacer.pause(Duration.ofMinutes(10));
		assertThat(pacer.remainingPause()).isEqualTo(Duration.ofMinutes(10));

		pacer.acquire();

		assertThat(now.get()).isEqualTo(Duration.ofMinutes(10).toNanos());
		assertThat(pacer.remainingPause()).isZero();
	}

}
