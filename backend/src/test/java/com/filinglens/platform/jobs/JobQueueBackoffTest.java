package com.filinglens.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class JobQueueBackoffTest {

	@Test
	void backoffGrowsExponentiallyAndIsCapped() {
		assertThat(JobQueue.backoff(1)).isEqualTo(Duration.ofSeconds(10));
		assertThat(JobQueue.backoff(2)).isEqualTo(Duration.ofSeconds(20));
		assertThat(JobQueue.backoff(3)).isEqualTo(Duration.ofSeconds(40));
		assertThat(JobQueue.backoff(10)).isEqualTo(Duration.ofHours(1));
		assertThat(JobQueue.backoff(1000)).isEqualTo(Duration.ofHours(1));
	}

}
