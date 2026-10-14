package com.filinglens.research.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaCitationSearchResultLocation;
import com.anthropic.models.beta.messages.BetaCitationsConfigParam;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaSearchResultBlockParam;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlock;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTextCitation;
import com.anthropic.models.beta.messages.MessageCreateParams;

/**
 * Answers with Claude via the official Java SDK. Retrieved passages are sent as
 * {@code search_result} content blocks with citations enabled, so Claude's
 * citations identify the exact passage and block range each claim comes from.
 * Server-side refusal fallbacks are enabled.
 */
@Component
class ClaudeAnswerModel implements AnswerModel {

	private static final Logger log = LoggerFactory.getLogger(ClaudeAnswerModel.class);

	private final ResearchProperties.Llm settings;

	private final AnthropicClient client;

	@Autowired
	ClaudeAnswerModel(ResearchProperties properties) {
		this(properties.llm(), properties.llm().configured()
				? AnthropicOkHttpClient.builder().apiKey(properties.llm().apiKey()).build() : null);
	}

	ClaudeAnswerModel(ResearchProperties.Llm settings, AnthropicClient client) {
		this.settings = settings;
		this.client = client;
	}

	@Override
	public boolean available() {
		return client != null;
	}

	@Override
	public Answer answer(AnswerRequest request, Consumer<String> onText) {
		if (client == null) {
			throw new IllegalStateException("No LLM API key is configured");
		}
		MessageCreateParams params = params(request);
		BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
		try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params)) {
			stream.stream().forEach(event -> {
				accumulator.accumulate(event);
				event.contentBlockDelta()
					.flatMap(delta -> delta.delta().text())
					.ifPresent(text -> onText.accept(text.text()));
			});
		}
		return toAnswer(accumulator.message());
	}

	MessageCreateParams params(AnswerRequest request) {
		MessageCreateParams.Builder builder = MessageCreateParams.builder()
			.model(settings.model())
			.maxTokens(settings.maxOutputTokens())
			.system(request.system())
			.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(settings.effort())).build())
			// Route refused requests to a fallback model chosen by the server.
			.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
			.fallbacksDefault();
		for (Turn turn : request.history()) {
			if (turn.fromUser()) {
				builder.addUserMessage(turn.text());
			}
			else {
				builder.addAssistantMessage(turn.text());
			}
		}
		List<BetaContentBlockParam> content = new ArrayList<>();
		for (Passage passage : request.passages()) {
			BetaSearchResultBlockParam.Builder result = BetaSearchResultBlockParam.builder()
				.source(passage.source())
				.title(passage.title())
				.citations(BetaCitationsConfigParam.builder().enabled(true).build());
			passage.blocks().forEach(text -> result.addContent(BetaTextBlockParam.builder().text(text).build()));
			content.add(BetaContentBlockParam.ofSearchResult(result.build()));
		}
		content.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(request.question()).build()));
		builder.addUserMessageOfBetaContentBlockParams(content);
		return builder.build();
	}

	private static Answer toAnswer(BetaMessage message) {
		List<Segment> segments = new ArrayList<>();
		for (BetaContentBlock block : message.content()) {
			block.text().ifPresent(text -> segments.add(segment(text)));
		}
		boolean refused = message.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false);
		if (refused) {
			message.stopDetails()
				.ifPresent(details -> log.warn("Answer refused: category={}", details.category().orElse(null)));
		}
		long cacheRead = message.usage().cacheReadInputTokens().orElse(0L);
		return new Answer(segments, refused, message.stopReason().map(Object::toString).orElse(null),
				message.model().toString(), message.usage().inputTokens(), message.usage().outputTokens(), cacheRead);
	}

	private static Segment segment(BetaTextBlock text) {
		List<Cite> cites = new ArrayList<>();
		for (BetaTextCitation citation : text.citations().orElse(List.of())) {
			citation.searchResultLocation().ifPresent(location -> cites.add(cite(location)));
		}
		return new Segment(text.text(), cites);
	}

	private static Cite cite(BetaCitationSearchResultLocation location) {
		return new Cite((int) location.searchResultIndex(), (int) location.startBlockIndex(),
				(int) location.endBlockIndex(), location.citedText());
	}

	@PreDestroy
	void close() {
		if (client != null) {
			client.close();
		}
	}

}
