package com.filinglens.ai;

import java.util.UUID;

/** Per-user LLM token accounting and the daily quota. */
public interface AiUsage {

	/** @throws QuotaExceededException if the user has used today's token allowance */
	void checkQuota(UUID userId);

	void record(UUID userId, String purpose, AiModel.AiResponse response);

}
