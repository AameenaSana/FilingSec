package com.filinglens.platform.jobs;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Durable, Postgres-backed job queue. Workers claim jobs with
 * {@code FOR UPDATE SKIP LOCKED}, so multiple application instances can process
 * the queue concurrently without double execution.
 */
@Component
public class JobQueue {

	static final int DEFAULT_MAX_ATTEMPTS = 5;

	private static final int MAX_ERROR_LENGTH = 2000;

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	JobQueue(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	/** Enqueues a job to run as soon as a worker is free. */
	public long enqueue(String type, Object payload) {
		return insert(type, payload, null, Duration.ZERO).orElseThrow();
	}

	/**
	 * Enqueues a job unless a pending or running job with the same dedupe key
	 * already exists.
	 * @return the new job id, or empty if an equivalent job is already queued
	 */
	public Optional<Long> enqueueUnique(String type, Object payload, String dedupeKey) {
		return insert(type, payload, dedupeKey, Duration.ZERO);
	}

	/** Like {@link #enqueueUnique(String, Object, String)}, but the job runs no earlier than {@code delay} from now. */
	public Optional<Long> enqueueUnique(String type, Object payload, String dedupeKey, Duration delay) {
		return insert(type, payload, dedupeKey, delay);
	}

	private Optional<Long> insert(String type, Object payload, String dedupeKey, Duration delay) {
		return jdbc.sql("""
				INSERT INTO job (type, payload, dedupe_key, max_attempts, run_after)
				VALUES (:type, CAST(:payload AS jsonb), :dedupeKey, :maxAttempts,
				        now() + make_interval(secs => :delaySeconds))
				ON CONFLICT (dedupe_key) WHERE status IN ('PENDING', 'RUNNING') DO NOTHING
				RETURNING id
				""")
			.param("type", type)
			.param("payload", jsonMapper.writeValueAsString(payload))
			.param("dedupeKey", dedupeKey)
			.param("maxAttempts", DEFAULT_MAX_ATTEMPTS)
			.param("delaySeconds", delay.toSeconds())
			.query(Long.class)
			.optional();
	}

	List<Job> claim(String workerId, int limit) {
		return jdbc.sql("""
				UPDATE job
				SET status = 'RUNNING', locked_by = :workerId, locked_at = now(),
				    attempts = attempts + 1, updated_at = now()
				WHERE id IN (
				    SELECT id FROM job
				    WHERE status = 'PENDING' AND run_after <= now()
				    ORDER BY priority DESC, run_after, id
				    LIMIT :limit
				    FOR UPDATE SKIP LOCKED)
				RETURNING id, type, payload::text AS payload, attempts, max_attempts
				""")
			.param("workerId", workerId)
			.param("limit", limit)
			.query((rs, row) -> new Job(rs.getLong("id"), rs.getString("type"), rs.getString("payload"),
					rs.getInt("attempts"), rs.getInt("max_attempts")))
			.list();
	}

	void markSucceeded(long jobId) {
		jdbc.sql("""
				UPDATE job
				SET status = 'SUCCEEDED', locked_by = NULL, locked_at = NULL, last_error = NULL,
				    finished_at = now(), updated_at = now()
				WHERE id = :id
				""").param("id", jobId).update();
	}

	/**
	 * Records a failure. The job is retried with exponential backoff until it has
	 * used all its attempts (or the failure is permanent), then it is marked FAILED.
	 */
	/** @return whether the job will not be retried */
	boolean markFailed(Job job, String error, boolean permanent) {
		boolean exhausted = permanent || job.attempt() >= job.maxAttempts();
		jdbc.sql("""
				UPDATE job
				SET status = :status, locked_by = NULL, locked_at = NULL, last_error = :error,
				    run_after = CASE WHEN :exhausted THEN run_after ELSE now() + make_interval(secs => :backoffSeconds) END,
				    finished_at = CASE WHEN :exhausted THEN now() END, updated_at = now()
				WHERE id = :id
				""")
			.param("status", exhausted ? "FAILED" : "PENDING")
			.param("error", truncate(error))
			.param("backoffSeconds", backoff(job.attempt()).toSeconds())
			.param("exhausted", exhausted)
			.param("id", job.id())
			.update();
		return exhausted;
	}

	/** Returns jobs whose worker died mid-execution to the queue. */
	int releaseStaleLocks(Duration lockTimeout) {
		return jdbc.sql("""
				UPDATE job
				SET status = 'PENDING', locked_by = NULL, locked_at = NULL, updated_at = now()
				WHERE status = 'RUNNING' AND locked_at < now() - make_interval(secs => :timeoutSeconds)
				""").param("timeoutSeconds", lockTimeout.toSeconds()).update();
	}

	static Duration backoff(int attempt) {
		long seconds = Math.min(10L << Math.min(attempt - 1, 20), 3600L);
		return Duration.ofSeconds(seconds);
	}

	private static String truncate(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
	}

}
