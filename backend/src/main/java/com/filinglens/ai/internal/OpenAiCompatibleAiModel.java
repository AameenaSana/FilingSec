package com.filinglens.ai.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.filinglens.ai.AiModel;
import com.filinglens.ai.AiNotConfiguredException;
import com.filinglens.ai.AiRequestRejectedException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AiModel} for services speaking the OpenAI Chat Completions API, such as
 * Gemini's OpenAI-compatible endpoint or a local Ollama. These have no citation
 * support of their own, so every passage block is labelled ({@code [2.3]}: block 3
 * of search result 2) and the model is told to put labels after each statement.
 * The labels become block-level citations and are removed from the text, also
 * while it streams. Rate limiting and server errors are retried with backoff,
 * honoring {@code Retry-After}, until the response starts.
 */
@Component
@ConditionalOnProperty(name = "filinglens.ai.provider", havingValue = "openai-compatible")
class OpenAiCompatibleAiModel implements AiModel {

	private static final int ATTEMPTS = 3;

	/** Longest wait honored from a {@code Retry-After} header. */
	private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

	static final String CITATION_INSTRUCTIONS = """


			Citations: every block of every search result starts with a label in square brackets, such as [2.3] \
			for block 3 of search result 2. Right after each statement, put the labels of the blocks that support \
			it, for example "Revenue grew [1.2][1.4]." Use only labels that appear in the search results, and never \
			put labels anywhere else.""";

	/** One label, or several separated by commas or semicolons, with the spaces before it. */
	private static final Pattern MARKER = Pattern.compile("[ \\t]*\\[(\\d+\\.\\d+(?:\\s*[,;]\\s*\\d+\\.\\d+)*)\\]");

	private static final Pattern LABEL = Pattern.compile("(\\d+)\\.(\\d+)");

	private final AiProperties settings;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	OpenAiCompatibleAiModel(AiProperties settings, JsonMapper json) {
		this.settings = settings;
		this.json = json;
	}

	@Override
	public boolean available() {
		return settings.configured() && settings.baseUrl() != null;
	}

	@Override
	public AiResponse generate(AiRequest request, Consumer<String> onText) {
		if (!available()) {
			throw new AiNotConfiguredException();
		}
		HttpRequest call = HttpRequest.newBuilder(endpoint(settings.baseUrl()))
			.timeout(Duration.ofMinutes(2))
			.header("Authorization", "Bearer " + settings.apiKey())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body(request))))
			.build();
		for (int attempt = 1;; attempt++) {
			Duration wait = Duration.ofSeconds(1L << attempt);
			try {
				HttpResponse<Stream<String>> response = http.send(call, HttpResponse.BodyHandlers.ofLines());
				if (response.statusCode() == 200) {
					try (Stream<String> lines = response.body()) {
						return read(lines.iterator(), request.passages(), onText);
					}
				}
				String error;
				try (Stream<String> lines = response.body()) {
					error = response.statusCode() + ": " + lines.collect(Collectors.joining("\n"));
				}
				if (ClaudeAiModel.isRejection(response.statusCode())) {
					throw new AiRequestRejectedException(response.statusCode(), error, null);
				}
				if (attempt >= ATTEMPTS) {
					throw new IllegalStateException("AI request failed with status " + error);
				}
				wait = retryAfter(response).orElse(wait);
			}
			catch (IOException ex) {
				if (attempt >= ATTEMPTS) {
					throw new IllegalStateException("AI request failed: " + ex.getMessage(), ex);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for the AI response", ex);
			}
			sleep(wait);
		}
	}

	/** Resolves against the base URL as a directory, whether or not it ends with a slash. */
	static URI endpoint(URI baseUrl) {
		String base = baseUrl.toString();
		return URI.create(base.endsWith("/") ? base : base + "/").resolve("chat/completions");
	}

	Map<String, Object> body(AiRequest request) {
		List<Map<String, String>> messages = new ArrayList<>();
		messages.add(Map.of("role", "system",
				"content", request.passages().isEmpty() ? request.system() : request.system() + CITATION_INSTRUCTIONS));
		for (Turn turn : request.history()) {
			messages.add(Map.of("role", turn.fromUser() ? "user" : "assistant", "content", turn.text()));
		}
		messages.add(Map.of("role", "user", "content", userContent(request)));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", settings.model());
		body.put("messages", messages);
		body.put("max_tokens", settings.maxOutputTokens());
		body.put("reasoning_effort", reasoningEffort(settings.effort()));
		body.put("stream", true);
		body.put("stream_options", Map.of("include_usage", true));
		return body;
	}

	/** The passages with labelled blocks, then the prompt. */
	static String userContent(AiRequest request) {
		StringBuilder content = new StringBuilder();
		for (int p = 0; p < request.passages().size(); p++) {
			Passage passage = request.passages().get(p);
			content.append("Search result ").append(p + 1).append(": ").append(passage.title()).append('\n');
			for (int b = 0; b < passage.blocks().size(); b++) {
				content.append('[').append(p + 1).append('.').append(b + 1).append("] ")
					.append(passage.blocks().get(b)).append('\n');
			}
			content.append('\n');
		}
		return content.append(request.prompt()).toString();
	}

	/** OpenAI-compatible services accept low, medium and high. */
	static String reasoningEffort(String effort) {
		return switch (effort) {
			case "xhigh", "max" -> "high";
			default -> effort;
		};
	}

	private AiResponse read(Iterator<String> lines, List<Passage> passages, Consumer<String> onText) {
		StringBuilder text = new StringBuilder();
		MarkerStripper stripper = new MarkerStripper(onText);
		String model = settings.model();
		String finishReason = null;
		long inputTokens = 0;
		long outputTokens = 0;
		long cachedTokens = 0;
		while (lines.hasNext()) {
			String line = lines.next();
			if (!line.startsWith("data:")) {
				continue;
			}
			String data = line.substring(5).strip();
			if (data.equals("[DONE]")) {
				break;
			}
			JsonNode chunk = json.readTree(data);
			if (chunk.hasNonNull("error")) {
				throw new IllegalStateException("AI response failed: " + chunk.get("error"));
			}
			if (chunk.hasNonNull("model")) {
				model = chunk.get("model").asString();
			}
			for (JsonNode choice : chunk.path("choices")) {
				JsonNode content = choice.path("delta").path("content");
				if (content.isString()) {
					text.append(content.asString());
					stripper.accept(content.asString());
				}
				if (choice.hasNonNull("finish_reason")) {
					finishReason = choice.get("finish_reason").asString();
				}
			}
			JsonNode usage = chunk.path("usage");
			if (usage.isObject()) {
				inputTokens = usage.path("prompt_tokens").asLong();
				outputTokens = usage.path("completion_tokens").asLong();
				cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asLong();
			}
		}
		stripper.finish();
		return new AiResponse(segments(text.toString(), passages), "content_filter".equals(finishReason), finishReason,
				model, inputTokens, outputTokens, cachedTokens);
	}

	/**
	 * Splits the text at its labels: the text before a label is a segment cited by
	 * it, and labels with nothing between them cite the same segment. Labels that do
	 * not name a block are dropped.
	 */
	static List<Segment> segments(String text, List<Passage> passages) {
		List<String> texts = new ArrayList<>();
		List<List<Cite>> cites = new ArrayList<>();
		Matcher marker = MARKER.matcher(text);
		int position = 0;
		while (marker.find()) {
			if (marker.start() > position || texts.isEmpty()) {
				texts.add(text.substring(position, marker.start()));
				cites.add(new ArrayList<>());
			}
			Matcher label = LABEL.matcher(marker.group(1));
			while (label.find()) {
				cite(Integer.parseInt(label.group(1)) - 1, Integer.parseInt(label.group(2)) - 1, passages)
					.ifPresent(cites.getLast()::add);
			}
			position = marker.end();
		}
		if (position < text.length()) {
			texts.add(text.substring(position));
			cites.add(List.of());
		}
		List<Segment> segments = new ArrayList<>();
		for (int i = 0; i < texts.size(); i++) {
			if (!texts.get(i).isEmpty() || !cites.get(i).isEmpty()) {
				segments.add(new Segment(texts.get(i), List.copyOf(cites.get(i))));
			}
		}
		return segments;
	}

	private static Optional<Cite> cite(int passage, int block, List<Passage> passages) {
		if (passage < 0 || passage >= passages.size() || block < 0
				|| block >= passages.get(passage).blocks().size()) {
			return Optional.empty();
		}
		return Optional.of(new Cite(passage, block, block + 1, passages.get(passage).blocks().get(block)));
	}

	/** The text with its labels removed. */
	static String strip(String text) {
		return MARKER.matcher(text).replaceAll("");
	}

	/**
	 * Passes streamed text on without labels. A chunk can end inside a label, or in
	 * spaces a label may follow, so that tail is held back until the next chunk.
	 */
	static final class MarkerStripper {

		private static final Pattern PARTIAL_TAIL = Pattern.compile("[ \\t]*(\\[[\\d.,; ]{0,40})?$");

		private final Consumer<String> onText;

		private final StringBuilder held = new StringBuilder();

		MarkerStripper(Consumer<String> onText) {
			this.onText = onText;
		}

		void accept(String text) {
			held.append(text);
			Matcher tail = PARTIAL_TAIL.matcher(held);
			int keep = tail.find() ? tail.start() : held.length();
			emit(held.substring(0, keep));
			held.delete(0, keep);
		}

		void finish() {
			emit(held.toString());
			held.setLength(0);
		}

		private void emit(String text) {
			String stripped = strip(text);
			if (!stripped.isEmpty()) {
				onText.accept(stripped);
			}
		}

	}

	/** The server's requested wait in seconds, capped; absent if missing or not a number of seconds. */
	static Optional<Duration> retryAfter(HttpResponse<?> response) {
		return response.headers().firstValue("Retry-After").flatMap(value -> {
			try {
				Duration wait = Duration.ofSeconds(Math.max(1, Long.parseLong(value.strip())));
				return Optional.of(wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait);
			}
			catch (NumberFormatException ex) {
				return Optional.empty();
			}
		});
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to retry", ex);
		}
	}

}
