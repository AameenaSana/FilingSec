package com.filinglens.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

		pollAndWait(worker(handler("test.ok", job -> {
			assertThat(job.payload()).contains("0000320193-24-000123");
			runs.incrementAndGet();
		})));

		assertThat(runs).hasValue(1);
		assertThat(status(id)).isEqualTo("SUCCEEDED");
	}

	@Test
	void failedJobIsRescheduledWithError() {
		long id = queue.enqueue("test.fail", Map.of());

		pollAndWait(worker(handler("test.fail", job -> {
			throw new IllegalStateException("boom");
		})));

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

		pollAndWait(worker(handler));

		assertThat(status(id)).isEqualTo("FAILED");
		assertThat(hookCalls).hasValue(1);
	}

	@Test
	void failureTheHandlerCallsPermanentIsNotRetried() {
		long id = queue.enqueue("test.rejected", Map.of());
		AtomicInteger hookCalls = new AtomicInteger();
		JobHandler handler = new JobHandler() {
			@Override
			public String type() {
				return "test.rejected";
			}

			@Override
			public void handle(Job job) {
				throw new UnsupportedOperationException("rejected by provider");
			}

			@Override
			public boolean isPermanent(Throwable cause) {
				return cause instanceof UnsupportedOperationException;
			}

			@Override
			public void onPermanentFailure(Job job, Throwable cause) {
				hookCalls.incrementAndGet();
			}
		};

		pollAndWait(worker(handler));

		assertThat(status(id)).isEqualTo("FAILED");
		assertThat(hookCalls).hasValue(1);
		assertThat(jdbc.sql("SELECT attempts FROM job WHERE id = :id").param("id", id).query(Integer.class).single())
			.isEqualTo(1);
		assertThat(jdbc.sql("SELECT run_after <= now() FROM job WHERE id = :id").param("id", id).query(Boolean.class).single())
			.as("a finished job is not scheduled again")
			.isTrue();
	}

	@Test
	void jobsClaimedTogetherRunConcurrently() {
		// Each job waits for the other to start, which only succeeds if they run at the same time.
		CountDownLatch bothStarted = new CountDownLatch(2);
		long first = queue.enqueue("test.together", Map.of());
		long second = queue.enqueue("test.together", Map.of());
		JobHandler handler = new JobHandler() {
			@Override
			public String type() {
				return "test.together";
			}

			@Override
			public boolean transactional() {
				return false;
			}

			@Override
			public void handle(Job job) throws InterruptedException {
				bothStarted.countDown();
				if (!bothStarted.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("jobs ran one after the other");
				}
			}
		};

		pollAndWait(worker(handler));

		assertThat(status(first)).isEqualTo("SUCCEEDED");
		assertThat(status(second)).isEqualTo("SUCCEEDED");
	}

	@Test
	void slowJobDoesNotHoldUpJobsQueuedAfterIt() throws InterruptedException {
		CountDownLatch finishSlow = new CountDownLatch(1);
		CountDownLatch quickRan = new CountDownLatch(1);
		JobWorker worker = worker(blocking("test.slow", finishSlow), handler("test.quick", job -> quickRan.countDown()));
		long slow = queue.enqueue("test.slow", Map.of());
		worker.poll();

		long quick = queue.enqueue("test.quick", Map.of());
		worker.poll();

		assertThat(quickRan.await(10, TimeUnit.SECONDS)).as("the quick job ran while the slow one was running").isTrue();
		assertThat(status(slow)).isEqualTo("RUNNING");
		finishSlow.countDown();
		worker.awaitIdle();
		assertThat(status(slow)).isEqualTo("SUCCEEDED");
		assertThat(status(quick)).isEqualTo("SUCCEEDED");
	}

	@Test
	void runningJobKeepsItsLockPastTheLockTimeout() throws InterruptedException {
		CountDownLatch finishSlow = new CountDownLatch(1);
		JobWorker worker = worker(blocking("test.slow", finishSlow));
		long id = queue.enqueue("test.slow", Map.of());
		worker.poll();
		jdbc.sql("UPDATE job SET locked_at = now() - interval '1 day' WHERE id = :id").param("id", id).update();

		worker.poll();

		assertThat(status(id)).as("the lock was renewed, not released as stale").isEqualTo("RUNNING");
		finishSlow.countDown();
		worker.awaitIdle();
		assertThat(status(id)).isEqualTo("SUCCEEDED");
		assertThat(jdbc.sql("SELECT attempts FROM job WHERE id = :id").param("id", id).query(Integer.class).single())
			.isEqualTo(1);
	}

	@Test
	void unknownJobTypeFailsPermanently() {
		long id = queue.enqueue("test.unknown", Map.of());

		pollAndWait(worker());

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

	private static void pollAndWait(JobWorker worker) {
		worker.poll();
		try {
			worker.awaitIdle();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** A handler whose job runs until {@code finish} is counted down. */
	private static JobHandler blocking(String type, CountDownLatch finish) {
		return new JobHandler() {
			@Override
			public String type() {
				return type;
			}

			@Override
			public boolean transactional() {
				return false;
			}

			@Override
			public void handle(Job job) throws InterruptedException {
				if (!finish.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("the test never finished the job");
				}
			}
		};
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
