package com.filinglens.platform.jobs;

/**
 * Executes jobs of one type. Implementations are Spring beans discovered by the
 * {@link JobWorker}. Handlers run inside a transaction and must be idempotent:
 * a job can be retried after a crash or a stale lock.
 */
public interface JobHandler {

	String type();

	void handle(Job job) throws Exception;

	/**
	 * Called once, in its own transaction, after the job's final attempt failed and
	 * the handler's work was rolled back. Use it to record a visible failure state.
	 */
	default void onPermanentFailure(Job job, Throwable cause) {
	}

}
