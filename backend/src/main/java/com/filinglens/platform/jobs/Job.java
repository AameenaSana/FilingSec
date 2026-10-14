package com.filinglens.platform.jobs;

/**
 * A claimed unit of background work. {@code payload} is the JSON document given at
 * enqueue time; {@code attempt} is 1 on the first run.
 */
public record Job(long id, String type, String payload, int attempt, int maxAttempts) {
}
