package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.filinglens.document.BlockText;
import com.filinglens.document.SectionText;
import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.ai.AiModel.Cite;
import com.filinglens.ai.AiModel.Segment;
import com.filinglens.research.internal.CitationMapper.MappedCitation;
import com.filinglens.research.internal.IndexRepository.ChunkRow;

class ChunkerAndCitationTest {

	@Test
	void chunksStayWithinSectionsAndNearTheTargetSize() {
		String paragraph = "word ".repeat(120).strip();
		List<SectionText> sections = List.of(section("10K.ITEM_1", "HEADING:Item 1. Business", paragraph, paragraph,
				paragraph, paragraph), section("10K.ITEM_1A", "HEADING:Item 1A. Risk Factors", paragraph));

		List<Chunker.Chunk> chunks = Chunker.chunk(sections);

		assertThat(chunks).extracting(Chunker.Chunk::sectionKey)
			.containsExactly("10K.ITEM_1", "10K.ITEM_1", "10K.ITEM_1A");
		assertThat(chunks).allSatisfy(c -> assertThat(Chunker.wordCount(c.text())).isLessThanOrEqualTo(Chunker.MAX_WORDS));
		assertThat(chunks.getFirst().blocks().getFirst().text()).isEqualTo("Item 1. Business");
		assertThat(chunks).extracting(Chunker.Chunk::ordinal).containsExactly(1, 2, 3);
	}

	@Test
	void headingsStartNewChunksOnceContentHasAccumulated() {
		String paragraph = "word ".repeat(150).strip();
		List<Chunker.Chunk> chunks = Chunker
			.chunk(List.of(section("10K.ITEM_7", paragraph, "HEADING:Liquidity and Capital Resources", paragraph)));

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(1).blocks().getFirst().text()).isEqualTo("Liquidity and Capital Resources");
	}

	@Test
	void mapsCitationsToExactBlocksAndVerifiesCitedText() {
		ChunkRow chunk = new ChunkRow(5, 11, "10K.ITEM_1A", "Risk Factors", List.of(40, 41, 42),
				List.of("10K.ITEM_1A/p1", "10K.ITEM_1A/p2", "10K.ITEM_1A/p3"),
				List.of("Item 1A. Risk Factors", "Generative AI features may not be successful.", "Tariffs may rise."));
		AiResponse answer = new AiResponse(List.of(new Segment("Apple warns that ", List.of()),
				new Segment("AI features may fail", List.of(new Cite(0, 1, 2, "Generative AI features may not be successful."))),
				new Segment(" and ", List.of()),
				new Segment("tariffs may rise.", List.of(new Cite(0, 2, 3, "Tariffs may rise."),
						new Cite(0, 1, 2, "Generative   AI features\nmay not be successful."),
						new Cite(0, 2, 3, "Invented quote not in the source."), new Cite(7, 0, 1, "out of range")))),
				false, "end_turn", "claude-opus-5-5", 100, 20, 0);

		List<MappedCitation> citations = CitationMapper.map(answer, List.of(chunk));

		assertThat(citations).hasSize(4);
		MappedCitation first = citations.getFirst();
		assertThat(first.blockRefs()).containsExactly("10K.ITEM_1A/p2");
		assertThat(first.firstBlockOrdinal()).isEqualTo(41);
		assertThat(first.answerStart()).isEqualTo("Apple warns that ".length());
		assertThat(first.answerEnd()).isEqualTo("Apple warns that AI features may fail".length());
		assertThat(first.verified()).isTrue();
		assertThat(citations.get(1).ordinal()).as("new source gets a new marker").isEqualTo(2);
		assertThat(citations.get(2).ordinal()).as("same source reuses its marker").isEqualTo(1);
		assertThat(citations.get(2).verified()).as("whitespace differences are ignored").isTrue();
		assertThat(citations.get(3).verified()).as("text not in the cited blocks").isFalse();
	}

	@Test
	void buildsOrQueriesAndVectorLiterals() {
		assertThat(IndexRepository.orQuery("What are Apple's AI-related risks?")).isEqualTo("what | are | apple | ai | related | risks");
		assertThat(IndexRepository.orQuery("!!")).isEmpty();
		assertThat(IndexRepository.vectorLiteral(new float[] { 0.5f, -1f })).isEqualTo("[0.5,-1.0]");
	}

	/** Blocks prefixed with "HEADING:" are headings; others are paragraphs. */
	static SectionText section(String key, String... texts) {
		List<BlockText> blocks = new ArrayList<>();
		for (String raw : texts) {
			boolean heading = raw.startsWith("HEADING:");
			String text = heading ? raw.substring("HEADING:".length()) : raw;
			int n = blocks.size() + 1;
			blocks.add(new BlockText(n, key + "/p" + n, heading ? "HEADING" : "PARAGRAPH", text, Integer.toString(n)));
		}
		return new SectionText(key, key, blocks);
	}

}
