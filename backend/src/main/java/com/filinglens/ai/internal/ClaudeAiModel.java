package com.filinglens.ai.internal;

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
import com.anthropic.errors.AnthropicServiceException;
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
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.filinglens.ai.AiModel;
import com.filinglens.ai.AiNotConfiguredException;
import com.filinglens.ai.AiRequestRejectedException;

/**
 * {@link AiModel} backed by Claude via the official Java SDK. Passages are sent
 * as {@code search_result} content blocks with citations enabled, so Claude's
 * citations identify the exact passage and block range each claim comes from.
 * Server-side refusal fallbacks are enabled.
 */
@Component
class ClaudeAiModel implements AiModel {

	private static final Logger log = LoggerFactory.getLogger(ClaudeAiModel.class);

	private final AiProperties settings;

	private final AnthropicClient client;

	@Autowired
	ClaudeAiModel(AiProperties settings) {
		this(settings, settings.configured() ? AnthropicOkHttpClient.builder().apiKey(settings.apiKey()).build() : null);
	}

	ClaudeAiModel(AiProperties settings, AnthropicClient client) {
		this.settings = settings;
		this.client = client;
	}

	@Override
	public boolean available() {
		return client != null;
	}

	@Override
	public AiResponse generate(AiRequest request, Consumer<String> onText) {
		if (client == null) {
			throw new AiNotConfiguredException();
		}
		BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
		try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params(request))) {
			stream.stream().forEach(event -> {
				accumulator.accumulate(event);
				event.contentBlockDelta()
					.flatMap(delta -> delta.delta().text())
					.ifPresent(text -> onText.accept(text.text()));
			});
		}
		catch (AnthropicServiceException ex) {
			if (isRejection(ex.statusCode())) {
				throw new AiRequestRejectedException(ex.statusCode(), ex.getMessage(), ex);
			}
			throw ex;
		}
		return toResponse(accumulator.message());
	}

	/**
	 * Client errors other than timeouts, conflicts and rate limiting (which the SDK
	 * already retries) fail the same way however often they are repeated.
	 */
	static boolean isRejection(int statusCode) {
		return statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 409 && statusCode != 429;
	}

	MessageCreateParams params(AiRequest request) {
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
		content.add(BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(request.prompt()).build()));
		builder.addUserMessageOfContentBlockParams(content);
		return builder.build();
	}

	private static AiResponse toResponse(BetaMessage message) {
		List<Segment> segments = new ArrayList<>();
		for (BetaContentBlock block : message.content()) {
			block.text().ifPresent(text -> segments.add(segment(text)));
		}
		boolean refused = message.stopReason().map(BetaStopReason.REFUSAL::equals).orElse(false);
		if (refused) {
			message.stopDetails()
				.ifPresent(details -> log.warn("Request refused: category={}", details.category().orElse(null)));
		}
		Tokens tokens = billedTokens(message.usage());
		return new AiResponse(segments, refused, message.stopReason().map(Object::toString).orElse(null),
				message.model().toString(), tokens.input(), tokens.output(), tokens.cacheRead());
	}

	/**
	 * Input, output and cache-read tokens across every attempt. After a refusal
	 * fallback the top-level usage covers only the attempt that produced the
	 * message, while the declined attempts are billed too and listed in
	 * {@code iterations}.
	 */
	static Tokens billedTokens(BetaUsage usage) {
		List<BetaUsage.Iteration> iterations = usage.iterations().orElse(List.of());
		if (iterations.isEmpty()) {
			return new Tokens(usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L));
		}
		Tokens total = new Tokens(0, 0, 0);
		for (BetaUsage.Iteration iteration : iterations) {
			if (iteration.message().isPresent()) {
				var attempt = iteration.message().get();
				total = total.plus(attempt.inputTokens(), attempt.outputTokens(), attempt.cacheReadInputTokens());
			}
			else if (iteration.fallbackMessage().isPresent()) {
				var attempt = iteration.fallbackMessage().get();
				total = total.plus(attempt.inputTokens(), attempt.outputTokens(), attempt.cacheReadInputTokens());
			}
		}
		return total;
	}

	record Tokens(long input, long output, long cacheRead) {

		Tokens plus(long input, long output, long cacheRead) {
			return new Tokens(this.input + input, this.output + output, this.cacheRead + cacheRead);
		}

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
