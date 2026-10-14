package com.filinglens.research.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.filinglens.document.BlockText;
import com.filinglens.document.SectionText;
import com.filinglens.research.internal.IndexRepository.ChunkRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The retrieval-augmented generation evaluation set in {@code rag-eval/}: a
 * fictional annual report and questions whose answers are in one known passage.
 * A passage is identified by an {@code expect} phrase that occurs in exactly one
 * block, so results stay valid when chunking changes.
 */
record RagEvalDataset(String company, List<SectionText> sections, List<Question> questions) {

	/** @param expect phrase from the answering block; null for questions the report cannot answer */
	record Question(String id, String question, String section, String expect) {

		boolean answerable() {
			return expect != null;
		}

	}

	static RagEvalDataset load() {
		JsonMapper json = JsonMapper.builder().build();
		JsonNode corpus = read(json, "/rag-eval/corpus.json");
		List<SectionText> sections = new ArrayList<>();
		for (JsonNode section : corpus.path("sections")) {
			String key = section.path("key").asString();
			List<BlockText> blocks = new ArrayList<>();
			for (JsonNode raw : section.path("blocks")) {
				String text = raw.asString();
				boolean heading = text.startsWith("HEADING:");
				int n = blocks.size() + 1;
				blocks.add(new BlockText(n, key + "/p" + n, heading ? "HEADING" : "PARAGRAPH",
						heading ? text.substring("HEADING:".length()) : text, Integer.toString(n)));
			}
			sections.add(new SectionText(key, section.path("title").asString(), blocks));
		}
		List<Question> questions = new ArrayList<>();
		for (JsonNode q : read(json, "/rag-eval/questions.json")) {
			questions.add(new Question(q.path("id").asString(), q.path("question").asString(),
					q.path("section").asString(null), q.path("expect").isNull() ? null : q.path("expect").asString()));
		}
		RagEvalDataset dataset = new RagEvalDataset(corpus.path("company").asString(), sections, questions);
		dataset.checkExpectationsAreUnique();
		return dataset;
	}

	/** The ref of the block containing the question's expected phrase. */
	String expectedRef(Question question) {
		return sections.stream()
			.flatMap(s -> s.blocks().stream())
			.filter(b -> b.text().contains(question.expect()))
			.map(BlockText::ref)
			.findFirst()
			.orElseThrow();
	}

	/** 1-based rank of the first chunk containing the expected block, or 0 if none does. */
	int rankOfExpected(Question question, List<ChunkRow> ranked) {
		String ref = expectedRef(question);
		for (int i = 0; i < ranked.size(); i++) {
			if (ranked.get(i).blockRefs().contains(ref)) {
				return i + 1;
			}
		}
		return 0;
	}

	private void checkExpectationsAreUnique() {
		for (Question q : questions) {
			if (!q.answerable()) {
				continue;
			}
			long matches = sections.stream()
				.flatMap(s -> s.blocks().stream())
				.filter(b -> b.text().contains(q.expect()))
				.count();
			if (matches != 1) {
				throw new IllegalStateException(
						"Question %s: expected phrase occurs in %d blocks, not exactly one".formatted(q.id(), matches));
			}
		}
	}

	static void writeReport(String name, String markdown) {
		try {
			Path dir = Path.of("build", "reports", "rag-eval");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(name), markdown);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static JsonNode read(JsonMapper json, String resource) {
		try (InputStream in = RagEvalDataset.class.getResourceAsStream(resource)) {
			return json.readTree(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
