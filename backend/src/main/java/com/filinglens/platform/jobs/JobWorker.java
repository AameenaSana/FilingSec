package com.filinglens.platform.jobs;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.filinglens.platform.config.FilingLensProperties;

/**
 * Polls the {@link JobQueue} and dispatches claimed jobs to their
 * {@link JobHandler}. Up to {@code batch-size} jobs run at once, each on its own
 * virtual thread; a poll fills only the free slots and does not wait for running
 * jobs, so a slow job (one waiting on an external service) does not hold up the
 * jobs queued after it. Each poll renews the locks of the running jobs, so only
 * jobs whose worker died are released as stale. Each job runs in its own
 * transaction unless its handler opts out; a failure rolls back the handler's work
 * and schedules a retry.
 */
@Component
@ConditionalOnBooleanProperty("filinglens.jobs.enabled")
class JobWorker {

	private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

	private final JobQueue queue;

	private final Map<String, JobHandler> handlers;

	private final TransactionTemplate transactionTemplate;

	private final FilingLensProperties.Jobs settings;

	private final String workerId;

	private final Semaphore slots;

	private final Set<Long> running = ConcurrentHashMap.newKeySet();

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	JobWorker(JobQueue queue, List<JobHandler> handlers, TransactionTemplate transactionTemplate,
			FilingLensProperties properties) {
		this.queue = queue;
		this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
		this.transactionTemplate = transactionTemplate;
		this.settings = properties.jobs();
		this.workerId = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
		this.slots = new Semaphore(settings.batchSize());
	}

	@Scheduled(fixedDelayString = "${filinglens.jobs.poll-interval}")
	void poll() {
		queue.refreshLocks(workerId, Set.copyOf(running));
		int released = queue.releaseStaleLocks(settings.lockTimeout());
		if (released > 0) {
			log.warn("Released {} stale job locks", released);
		}
		// Only this method acquires slots and polls never overlap, so the free slots cannot shrink meanwhile.
		int free = slots.availablePermits();
		if (free == 0) {
			return;
		}
		for (Job job : queue.claim(workerId, free)) {
			slots.acquireUninterruptibly();
			running.add(job.id());
			executor.execute(() -> {
				try {
					execute(job);
				}
				finally {
					running.remove(job.id());
					slots.release();
				}
			});
		}
	}

	/** Blocks until no job is running; for tests, which poll and then check the outcome. */
	void awaitIdle() throws InterruptedException {
		slots.acquire(settings.batchSize());
		slots.release(settings.batchSize());
	}

	/** Interrupts running jobs so shutdown is not held up; an interrupted job is retried later. */
	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

	void execute(Job job) {
		MDC.put("jobId", Long.toString(job.id()));
		MDC.put("jobType", job.type());
		JobHandler handler = handlers.get(job.type());
		try {
			if (handler == null) {
				log.error("No handler registered for job type {}", job.type());
				queue.markFailed(job, "No handler registered for job type " + job.type(), true);
				return;
			}
			long start = System.nanoTime();
			if (handler.transactional()) {
				transactionTemplate.executeWithoutResult(status -> run(handler, job));
			}
			else {
				run(handler, job);
			}
			queue.markSucceeded(job.id());
			log.info("Job succeeded in {} ms (attempt {})", (System.nanoTime() - start) / 1_000_000, job.attempt());
		}
		catch (RuntimeException ex) {
			Throwable cause = ex instanceof JobExecutionException ? ex.getCause() : ex;
			boolean permanent = handler == null || handler.isPermanent(cause);
			log.warn("Job failed (attempt {}/{}{})", job.attempt(), job.maxAttempts(),
					permanent ? ", not retryable" : "", cause);
			boolean exhausted = queue.markFailed(job, cause.getClass().getSimpleName() + ": " + cause.getMessage(),
					permanent);
			if (exhausted && handler != null) {
				notifyPermanentFailure(handler, job, cause);
			}
		}
		finally {
			MDC.remove("jobId");
			MDC.remove("jobType");
		}
	}

	private static void run(JobHandler handler, Job job) {
		try {
			handler.handle(job);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new JobExecutionException(ex);
		}
	}

	private void notifyPermanentFailure(JobHandler handler, Job job, Throwable cause) {
		try {
			transactionTemplate.executeWithoutResult(status -> handler.onPermanentFailure(job, cause));
		}
		catch (RuntimeException ex) {
			log.error("Permanent-failure hook failed for job {}", job.id(), ex);
		}
	}

	private static String hostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			return "worker";
		}
	}

	private static final class JobExecutionException extends RuntimeException {

		JobExecutionException(Throwable cause) {
			super(cause);
		}

	}

}
