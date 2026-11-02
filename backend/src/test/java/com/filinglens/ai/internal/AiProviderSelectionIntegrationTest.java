package com.filinglens.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.filinglens.IntegrationTest;
import com.filinglens.ai.AiModel;

@IntegrationTest
@TestPropertySource(properties = { "filinglens.ai.provider=openai-compatible", "filinglens.ai.api-key=test-key",
		"filinglens.ai.model=gemini-3.8-flash" })
class AiProviderSelectionIntegrationTest {

	@Autowired
	AiModel model;

	@Test
	void openAiCompatibleProviderReplacesClaude() {
		assertThat(model).isInstanceOf(OpenAiCompatibleAiModel.class);
		assertThat(model.available()).isTrue();
	}

}
