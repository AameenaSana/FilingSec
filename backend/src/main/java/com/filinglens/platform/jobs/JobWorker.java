package com.filinglens.platform.jobs;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * {@link JobHandler}. Each job runs in its own transaction; a failure rolls back
 * the handler's work and schedules a retry.
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

	JobWorker(JobQueue queue, List<JobHandler> handlers, TransactionTemplate transactionTemplate,
			FilingLensProperties properties) {
		this.queue = queue;
		this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
		this.transactionTemplate = transactionTemplate;
		this.settings = properties.jobs();
		this.workerId = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Scheduled(fixedDelayString = "${filinglens.jobs.poll-interval}")
	void poll() {
		int released = queue.releaseStaleLocks(settings.lockTimeout());
		if (released > 0) {
			log.warn("Released {} stale job locks", released);
		}
		for (Job job : queue.claim(workerId, settings.batchSize())) {
			execute(job);
		}
	}

	void execute(Job job) {
		MDC.put("jobId", Long.toString(job.id()));
		MDC.put("jobType", job.type());
		try {
			JobHandler handler = handlers.get(job.type());
			if (handler == null) {
				log.error("No handler registered for job type {}", job.type());
				queue.markFailed(job, "No handler registered for job type " + job.type(), true);
				return;
			}
			long start = System.nanoTime();
			transactionTemplate.executeWithoutResult(status -> {
				try {
					handler.handle(job);
				}
				catch (RuntimeException ex) {
					throw ex;
				}
				catch (Exception ex) {
					throw new JobExecutionException(ex);
				}
			});
			queue.markSucceeded(job.id());
			log.info("Job succeeded in {} ms (attempt {})", (System.nanoTime() - start) / 1_000_000, job.attempt());
		}
		catch (RuntimeException ex) {
			Throwable cause = ex instanceof JobExecutionException ? ex.getCause() : ex;
			log.warn("Job failed (attempt {}/{})", job.attempt(), job.maxAttempts(), cause);
			boolean exhausted = queue.markFailed(job, cause.getClass().getSimpleName() + ": " + cause.getMessage(),
					false);
			if (exhausted) {
				notifyPermanentFailure(job, cause);
			}
		}
		finally {
			MDC.remove("jobId");
			MDC.remove("jobType");
		}
	}

	private void notifyPermanentFailure(Job job, Throwable cause) {
		JobHandler handler = handlers.get(job.type());
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
