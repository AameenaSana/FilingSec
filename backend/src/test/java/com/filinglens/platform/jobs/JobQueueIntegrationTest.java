package com.filinglens.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.filinglens.IntegrationTest;
import com.filinglens.platform.config.FilingLensProperties;

@IntegrationTest
class JobQueueIntegrationTest {

	@Autowired
	JobQueue queue;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	FilingLensProperties properties;

	@BeforeEach
	void clean() {
		jdbc.sql("DELETE FROM job").update();
	}

	@Test
	void runsJobAndMarksItSucceeded() {
		AtomicInteger runs = new AtomicInteger();
		long id = queue.enqueue("test.ok", Map.of("accession", "0000320193-24-000123"));

		worker(handler("test.ok", job -> {
			assertThat(job.payload()).contains("0000320193-24-000123");
			runs.incrementAndGet();
		})).poll();

		assertThat(runs).hasValue(1);
		assertThat(status(id)).isEqualTo("SUCCEEDED");
	}

	@Test
	void failedJobIsRescheduledWithError() {
		long id = queue.enqueue("test.fail", Map.of());

		worker(handler("test.fail", job -> {
			throw new IllegalStateException("boom");
		})).poll();

		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(jdbc.sql("SELECT last_error FROM job WHERE id = :id").param("id", id).query(String.class).single())
			.contains("boom");
		// Backoff means it is not immediately claimable again.
		assertThat(queue.claim("other", 10)).isEmpty();
	}

	@Test
	void permanentFailureHookRunsOnceAfterTheLastAttempt() {
		long id = queue.enqueue("test.last", Map.of());
		jdbc.sql("UPDATE job SET max_attempts = 1 WHERE id = :id").param("id", id).update();
		AtomicInteger hookCalls = new AtomicInteger();
		JobHandler handler = new JobHandler() {
			@Override
			public String type() {
				return "test.last";
			}

			@Override
			public void handle(Job job) {
				throw new IllegalStateException("always fails");
			}

			@Override
			public void onPermanentFailure(Job job, Throwable cause) {
				assertThat(cause).hasMessage("always fails");
				hookCalls.incrementAndGet();
			}
		};

		worker(handler).poll();

		assertThat(status(id)).isEqualTo("FAILED");
		assertThat(hookCalls).hasValue(1);
	}

	@Test
	void unknownJobTypeFailsPermanently() {
		long id = queue.enqueue("test.unknown", Map.of());

		worker().poll();

		assertThat(status(id)).isEqualTo("FAILED");
	}

	@Test
	void dedupeKeyPreventsDuplicateActiveJobs() {
		assertThat(queue.enqueueUnique("test.ok", Map.of(), "filing:1")).isPresent();
		assertThat(queue.enqueueUnique("test.ok", Map.of(), "filing:1")).isEmpty();
	}

	@Test
	void claimedJobsAreNotClaimedTwice() {
		queue.enqueue("test.ok", Map.of());

		assertThat(queue.claim("worker-a", 10)).hasSize(1);
		assertThat(queue.claim("worker-b", 10)).isEmpty();
	}

	private JobWorker worker(JobHandler... handlers) {
		return new JobWorker(queue, List.of(handlers), transactionTemplate, properties);
	}

	private String status(long id) {
		return jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", id).query(String.class).single();
	}

	private static JobHandler handler(String type, ThrowingConsumer body) {
		return new JobHandler() {
			@Override
			public String type() {
				return type;
			}

			@Override
			public void handle(Job job) throws Exception {
				body.accept(job);
			}
		};
	}

	@FunctionalInterface
	interface ThrowingConsumer {

		void accept(Job job) throws Exception;

	}

}
