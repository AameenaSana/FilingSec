package com.filinglens.research.internal;

import java.util.ArrayList;
import java.util.List;

import com.filinglens.document.BlockText;
import com.filinglens.document.SectionText;

/**
 * Groups a section's consecutive blocks into retrieval chunks of roughly
 * {@link #TARGET_WORDS} words. Chunks never cross sections, so every passage has a
 * single section for its citation. A heading starts a new chunk when the current
 * one already has content, keeping headings with the text they introduce.
 */
final class Chunker {

	static final int TARGET_WORDS = 350;

	static final int MAX_WORDS = 600;

	record Chunk(int ordinal, String sectionKey, String sectionTitle, List<BlockText> blocks) {

		String text() {
			return String.join("\n\n", blocks.stream().map(BlockText::text).toList());
		}

	}

	private Chunker() {
	}

	static List<Chunk> chunk(List<SectionText> sections) {
		List<Chunk> chunks = new ArrayList<>();
		for (SectionText section : sections) {
			List<BlockText> current = new ArrayList<>();
			int words = 0;
			for (BlockText block : section.blocks()) {
				int blockWords = wordCount(block.text());
				boolean headingStartsNew = "HEADING".equals(block.type()) && words >= TARGET_WORDS / 3;
				if (!current.isEmpty() && (words + blockWords > MAX_WORDS || words >= TARGET_WORDS || headingStartsNew)) {
					chunks.add(new Chunk(chunks.size() + 1, section.key(), section.title(), List.copyOf(current)));
					current.clear();
					words = 0;
				}
				current.add(block);
				words += blockWords;
			}
			if (!current.isEmpty()) {
				chunks.add(new Chunk(chunks.size() + 1, section.key(), section.title(), List.copyOf(current)));
			}
		}
		return chunks;
	}

	static int wordCount(String text) {
		String stripped = text.strip();
		return stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
	}

}
