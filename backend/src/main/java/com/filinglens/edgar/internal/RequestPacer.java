package com.filinglens.edgar.internal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Spaces requests evenly so that no one-second window ever exceeds the configured
 * rate (unlike a bursty token bucket), and supports a global pause.
 */
class RequestPacer {

	interface Sleeper {

		void sleep(long nanos) throws InterruptedException;

	}

	private final long intervalNanos;

	private final LongSupplier nanoClock;

	private final Sleeper sleeper;

	private long nextSlot;

	private volatile long pausedUntil;

	RequestPacer(int requestsPerSecond) {
		this(requestsPerSecond, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
	}

	RequestPacer(int requestsPerSecond, LongSupplier nanoClock, Sleeper sleeper) {
		this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / requestsPerSecond;
		this.nanoClock = nanoClock;
		this.sleeper = sleeper;
		this.nextSlot = nanoClock.getAsLong();
		this.pausedUntil = nextSlot;
	}

	/** Blocks until the caller may send the next request. */
	void acquire() throws InterruptedException {
		long wait;
		synchronized (this) {
			long now = nanoClock.getAsLong();
			long slot = Math.max(Math.max(now, nextSlot), pausedUntil);
			nextSlot = slot + intervalNanos;
			wait = slot - now;
		}
		if (wait > 0) {
			sleeper.sleep(wait);
		}
	}

	/** Remaining pause, or zero when traffic is allowed. */
	Duration remainingPause() {
		return Duration.ofNanos(Math.max(0, pausedUntil - nanoClock.getAsLong()));
	}

	void pause(Duration duration) {
		synchronized (this) {
			pausedUntil = Math.max(pausedUntil, nanoClock.getAsLong() + duration.toNanos());
		}
	}

}
