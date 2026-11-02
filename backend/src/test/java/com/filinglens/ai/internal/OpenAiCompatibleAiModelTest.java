package com.filinglens.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.filinglens.ai.AiModel.AiRequest;
import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.ai.AiModel.Cite;
import com.filinglens.ai.AiModel.Passage;
import com.filinglens.ai.AiModel.Segment;
import com.filinglens.ai.AiModel.Turn;
import com.filinglens.ai.AiNotConfiguredException;
import com.filinglens.ai.AiRequestRejectedException;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the client against a local server speaking the Chat Completions
 * streaming protocol: request shape (labelled passages, citation instructions),
 * label parsing into citations, streaming without labels, and error handling.
 */
class OpenAiCompatibleAiModelTest {

	static final String STREAM = """
			data: {"id":"1","model":"gemini-3.8-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"Apple cites risks from generative AI [1"}}]}

			data: {"id":"1","model":"gemini-3.8-flash","choices":[{"index":0,"delta":{"content":".2]. Supply is concentrated [2.1][1.1]."},"finish_reason":"stop"}]}

			data: {"id":"1","model":"gemini-3.8-flash","choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":42,"prompt_tokens_details":{"cached_tokens":100}}}

			data: [DONE]

			""";

	record Reply(int status, String body, String retryAfter) {
	}

	HttpServer server;

	final Queue<Reply> replies = new ConcurrentLinkedQueue<>();

	final AtomicReference<String> requestBody = new AtomicReference<>();

	final AtomicReference<String> requestPath = new AtomicReference<>();

	final AtomicReference<String> authorization = new AtomicReference<>();

	OpenAiCompatibleAiModel model;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			requestPath.set(exchange.getRequestURI().getPath());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			Reply reply = replies.isEmpty() ? new Reply(200, STREAM, null) : replies.poll();
			byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", reply.status() == 200 ? "text/event-stream" : "application/json");
			if (reply.retryAfter() != null) {
				exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
			}
			exchange.sendResponseHeaders(reply.status(), body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		model = model("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/openai");
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void streamsTextWithoutLabelsAndMapsLabelsToBlockCitations() {
		List<String> streamed = new ArrayList<>();
		AiResponse answer = model.generate(request(), streamed::add);

		String expected = "Apple cites risks from generative AI. Supply is concentrated.";
		assertThat(String.join("", streamed)).isEqualTo(expected);
		assertThat(answer.text()).isEqualTo(expected);
		assertThat(answer.segments()).containsExactly(
				new Segment("Apple cites risks from generative AI",
						List.of(new Cite(0, 1, 2, "Generative AI features may not be successful."))),
				new Segment(". Supply is concentrated",
						List.of(new Cite(1, 0, 1, "Components come from single sources."),
								new Cite(0, 0, 1, "The Company faces intense competition."))),
				new Segment(".", List.of()));
		assertThat(answer.refused()).isFalse();
		assertThat(answer.stopReason()).isEqualTo("stop");
		assertThat(answer.model()).isEqualTo("gemini-3.8-flash");
		assertThat(answer.inputTokens()).isEqualTo(1200);
		assertThat(answer.outputTokens()).isEqualTo(42);
		assertThat(answer.cacheReadTokens()).isEqualTo(100);
	}

	@Test
	void sendsLabelledPassagesWithCitationInstructions() {
		model.generate(request(), text -> {
		});

		assertThat(requestPath.get()).isEqualTo("/v1beta/openai/chat/completions");
		assertThat(authorization.get()).isEqualTo("Bearer test-key");
		JsonNode body = JsonMapper.builder().build().readTree(requestBody.get());
		assertThat(body.path("model").asString()).isEqualTo("gemini-3.8-flash");
		assertThat(body.path("stream").asBoolean()).isTrue();
		assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
		assertThat(body.path("reasoning_effort").asString()).isEqualTo("medium");

		JsonNode messages = body.path("messages");
		assertThat(messages.size()).isEqualTo(4);
		assertThat(messages.get(0).path("role").asString()).isEqualTo("system");
		assertThat(messages.get(0).path("content").asString()).startsWith("Answer from the passages.")
			.contains("[2.3] for block 3 of search result 2");
		assertThat(messages.get(1).path("role").asString()).isEqualTo("user");
		assertThat(messages.get(2).path("role").asString()).isEqualTo("assistant");
		assertThat(messages.get(3).path("content").asString()).isEqualTo("""
				Search result 1: Apple Inc. 10-K — Risk Factors
				[1.1] The Company faces intense competition.
				[1.2] Generative AI features may not be successful.

				Search result 2: Apple Inc. 10-K — Supply
				[2.1] Components come from single sources.

				What are the AI risks?""");
	}

	@Test
	void dropsLabelsThatNameNoBlock() {
		List<Passage> passages = request().passages();

		assertThat(OpenAiCompatibleAiModel.segments("Claim [3.1][1.9][1.1].", passages)).containsExactly(
				new Segment("Claim", List.of(new Cite(0, 0, 1, "The Company faces intense competition."))),
				new Segment(".", List.of()));
	}

	@Test
	void acceptsSeveralLabelsInOneBracket() {
		assertThat(OpenAiCompatibleAiModel.segments("Claim [1.1, 2.1].", request().passages()).getFirst().citations())
			.extracting(Cite::passageIndex)
			.containsExactly(0, 1);
	}

	@Test
	void keepsBracketsThatAreNotLabels() {
		List<String> streamed = new ArrayList<>();
		var stripper = new OpenAiCompatibleAiModel.MarkerStripper(streamed::add);
		for (String chunk : List.of("Net sales [in millions] rose ", "[", "1.", "1]", " and fell [2", ".1].")) {
			stripper.accept(chunk);
		}
		stripper.finish();

		assertThat(String.join("", streamed)).isEqualTo("Net sales [in millions] rose and fell.");
	}

	@Test
	void contentFilterFinishIsARefusal() {
		replies.add(new Reply(200, """
				data: {"choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}

				data: [DONE]

				""", null));

		AiResponse answer = model.generate(request(), text -> {
		});

		assertThat(answer.refused()).isTrue();
		assertThat(answer.segments()).isEmpty();
	}

	@Test
	void rejectedRequestIsNotRetried() {
		replies.add(new Reply(400, "{\"error\":{\"message\":\"API key not valid\"}}", null));

		assertThatThrownBy(() -> model.generate(request(), text -> {
		})).isInstanceOf(AiRequestRejectedException.class).hasMessageContaining("API key not valid");
		assertThat(replies).isEmpty();
	}

	@Test
	void rateLimitedRequestIsRetriedAfterTheRequestedWait() {
		replies.add(new Reply(429, "{\"error\":{\"message\":\"Resource exhausted\"}}", "1"));

		AiResponse answer = model.generate(request(), text -> {
		});

		assertThat(answer.text()).startsWith("Apple cites");
	}

	@Test
	void withoutAnApiKeyItIsNotAvailable() {
		OpenAiCompatibleAiModel unconfigured = model("", "http://127.0.0.1:1/");

		assertThat(unconfigured.available()).isFalse();
		assertThatThrownBy(() -> unconfigured.generate(request(), text -> {
		})).isInstanceOf(AiNotConfiguredException.class);
	}

	@Test
	void mapsEffortToTheLevelsOpenAiCompatibleServicesAccept() {
		assertThat(OpenAiCompatibleAiModel.reasoningEffort("max")).isEqualTo("high");
		assertThat(OpenAiCompatibleAiModel.reasoningEffort("xhigh")).isEqualTo("high");
		assertThat(OpenAiCompatibleAiModel.reasoningEffort("low")).isEqualTo("low");
	}

	private static OpenAiCompatibleAiModel model(String apiKey, String baseUrl) {
		return new OpenAiCompatibleAiModel(new AiProperties(apiKey, "gemini-3.8-flash", 64000, "medium", 1_000_000,
				"openai-compatible", URI.create(baseUrl)), JsonMapper.builder().build());
	}

	private static AiRequest request() {
		return new AiRequest("Answer from the passages.",
				List.of(new Turn(true, "Hi"), new Turn(false, "Hello")),
				List.of(new Passage("Apple Inc. 10-K — Risk Factors", "a#1",
						List.of("The Company faces intense competition.", "Generative AI features may not be successful.")),
						new Passage("Apple Inc. 10-K — Supply", "a#2", List.of("Components come from single sources."))),
				"What are the AI risks?");
	}

}
