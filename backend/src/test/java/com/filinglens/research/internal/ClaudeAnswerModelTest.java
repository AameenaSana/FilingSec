package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.filinglens.research.internal.AnswerModel.Answer;
import com.filinglens.research.internal.AnswerModel.AnswerRequest;
import com.filinglens.research.internal.AnswerModel.Cite;
import com.filinglens.research.internal.AnswerModel.Passage;
import com.filinglens.research.internal.AnswerModel.Turn;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the SDK integration end to end against a local server speaking the
 * Messages API streaming protocol: request shape (search results with citations,
 * fallbacks, effort) and parsing of streamed text and citations.
 */
class ClaudeAnswerModelTest {

	static final String STREAM = """
			event: message_start
			data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1200,"output_tokens":1,"cache_read_input_tokens":0}}}

			event: content_block_start
			data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

			event: content_block_delta
			data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Apple cites "}}

			event: content_block_stop
			data: {"type":"content_block_stop","index":0}

			event: content_block_start
			data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":"","citations":[]}}

			event: content_block_delta
			data: {"type":"content_block_delta","index":1,"delta":{"type":"citations_delta","citation":{"type":"search_result_location","cited_text":"generative AI features may not be successful","search_result_index":0,"source":"0000320193-25-000079#10K.ITEM_1A/p3","title":"Apple Inc. 10-K","start_block_index":1,"end_block_index":2}}}

			event: content_block_delta
			data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"risks from generative AI features."}}

			event: content_block_stop
			data: {"type":"content_block_stop","index":1}

			event: message_delta
			data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":42}}

			event: message_stop
			data: {"type":"message_stop"}

			""";

	HttpServer server;

	final AtomicReference<String> requestBody = new AtomicReference<>();

	final AtomicReference<String> betaHeader = new AtomicReference<>();

	ClaudeAnswerModel model;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			betaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
			byte[] body = STREAM.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		var client = AnthropicOkHttpClient.builder()
			.apiKey("test-key")
			.baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
			.build();
		model = new ClaudeAnswerModel(new ResearchProperties.Llm("test-key", "claude-opus-5-5", 64000, "medium"), client);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void streamsTextAndMapsSearchResultCitations() {
		List<String> streamed = new ArrayList<>();
		Answer answer = model.answer(request(), streamed::add);

		assertThat(String.join("", streamed)).isEqualTo("Apple cites risks from generative AI features.");
		assertThat(answer.text()).isEqualTo("Apple cites risks from generative AI features.");
		assertThat(answer.segments()).hasSize(2);
		assertThat(answer.segments().get(1).citations())
			.containsExactly(new Cite(0, 1, 2, "generative AI features may not be successful"));
		assertThat(answer.refused()).isFalse();
		assertThat(answer.inputTokens()).isEqualTo(1200);
		assertThat(answer.outputTokens()).isEqualTo(42);
	}

	@Test
	void sendsPassagesAsCitableSearchResultsWithFallbacksEnabled() {
		model.answer(request(), text -> {
		});

		JsonNode body = JsonMapper.builder().build().readTree(requestBody.get());
		assertThat(body.path("model").asString()).isEqualTo("claude-opus-5-5");
		assertThat(body.path("stream").asBoolean()).isTrue();
		assertThat(body.path("output_config").path("effort").asString()).isEqualTo("medium");
		assertThat(body.path("fallbacks").asString()).isEqualTo("default");
		assertThat(betaHeader.get()).contains("server-side-fallback-2026-07-01");
		assertThat(body.path("system").asString()).isEqualTo("Answer from the passages.");

		JsonNode messages = body.path("messages");
		assertThat(messages.size()).isEqualTo(3);
		assertThat(messages.get(0).path("role").asString()).isEqualTo("user");
		assertThat(messages.get(1).path("role").asString()).isEqualTo("assistant");
		JsonNode content = messages.get(2).path("content");
		JsonNode searchResult = content.get(0);
		assertThat(searchResult.path("type").asString()).isEqualTo("search_result");
		assertThat(searchResult.path("citations").path("enabled").asBoolean()).isTrue();
		assertThat(searchResult.path("title").asString()).isEqualTo("Apple Inc. 10-K (period ended 2025-09-27) — Risk Factors");
		assertThat(searchResult.path("content").size()).isEqualTo(2);
		assertThat(searchResult.path("content").get(1).path("text").asString())
			.isEqualTo("The Company's generative AI features may not be successful.");
		assertThat(content.get(1).path("text").asString()).isEqualTo("What AI risks does Apple describe?");
	}

	static AnswerRequest request() {
		return new AnswerRequest("Answer from the passages.",
				List.of(new Turn(true, "Earlier question"), new Turn(false, "Earlier answer")),
				List.of(new Passage("Apple Inc. 10-K (period ended 2025-09-27) — Risk Factors",
						"0000320193-25-000079#10K.ITEM_1A/p2",
						List.of("Item 1A. Risk Factors", "The Company's generative AI features may not be successful."))),
				"What AI risks does Apple describe?");
	}

}
