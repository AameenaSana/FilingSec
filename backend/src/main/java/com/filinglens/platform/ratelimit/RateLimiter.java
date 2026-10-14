package com.filinglens.platform.ratelimit;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.filinglens.platform.error.TooManyRequestsException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

/**
 * In-memory token-bucket rate limiting keyed by an arbitrary string (for example
 * a client IP). Buckets for idle keys are evicted. Limits are per application
 * instance, which is sufficient for abuse throttling; durable protections such as
 * account lockout live in the database.
 */
@Component
public class RateLimiter {

	private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
		.expireAfterAccess(Duration.ofHours(1))
		.maximumSize(100_000)
		.build();

	/**
	 * A named limit: at most {@code capacity} requests per {@code period}, refilled
	 * gradually.
	 */
	public record Limit(String name, long capacity, Duration period) {
	}

	public boolean tryAcquire(Limit limit, String key) {
		return buckets.get(limit.name() + ":" + key, k -> newBucket(limit)).tryConsume(1);
	}

	/** @throws TooManyRequestsException if the limit is exhausted for this key */
	public void acquireOrThrow(Limit limit, String key) {
		if (!tryAcquire(limit, key)) {
			throw new TooManyRequestsException();
		}
	}

	private static Bucket newBucket(Limit limit) {
		return Bucket.builder()
			.addLimit(Bandwidth.builder().capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()).build())
			.build();
	}

}
