package com.filinglens.platform.jobs;

/**
 * Executes jobs of one type. Implementations are Spring beans discovered by the
 * {@link JobWorker}. Handlers run inside a transaction (unless they opt out with
 * {@link #transactional()}) and must be idempotent: a job can be retried after a
 * crash or a stale lock.
 */
public interface JobHandler {

	String type();

	void handle(Job job) throws Exception;

	/**
	 * Whether {@link #handle} runs inside one transaction. Handlers that spend most
	 * of their time calling external services (and waiting to retry them) return
	 * false so they do not hold a database connection meanwhile; they must then make
	 * their own writes atomic where needed.
	 */
	default boolean transactional() {
		return true;
	}

	/**
	 * Whether a failure can never succeed on retry (for example a request the
	 * provider rejected as invalid), so the job fails at once instead of using up its
	 * remaining attempts.
	 */
	default boolean isPermanent(Throwable cause) {
		return false;
	}

	/**
	 * Called once, in its own transaction, after the job's final attempt failed and
	 * the handler's work was rolled back. Use it to record a visible failure state.
	 */
	default void onPermanentFailure(Job job, Throwable cause) {
	}

}
