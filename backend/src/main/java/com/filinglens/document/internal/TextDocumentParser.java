package com.filinglens.document.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jsoup.nodes.Entities;

/**
 * Extracts blocks from plain-text filings (common before 2001): SGML wrapper tags
 * and {@code <PAGE>} markers are dropped and paragraphs are separated by blank
 * lines. The viewer copy shows each paragraph as preformatted text, preserving the
 * original column layout of tables.
 */
final class TextDocumentParser {

	private static final Pattern TAG_LINE = Pattern.compile("(?m)^\\s*</?[A-Z][A-Z0-9-]*>.*$");

	private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\r?\\n\\s*\\r?\\n");

	private TextDocumentParser() {
	}

	static ParseResult parse(String text) {
		String body = TAG_LINE.matcher(text.replace("\f", "\n\n")).replaceAll("");
		List<ParsedBlock> blocks = new ArrayList<>();
		StringBuilder html = new StringBuilder();
		for (String paragraph : PARAGRAPH_BREAK.split(body)) {
			String normalized = TextNormalizer.normalize(paragraph);
			if (TextNormalizer.isPageArtifact(normalized)) {
				continue;
			}
			int ordinal = blocks.size() + 1;
			BlockType type = classify(paragraph, normalized);
			blocks.add(new ParsedBlock(ordinal, type, normalized));
			html.append("<div ")
				.append(HtmlDocumentParser.BLOCK_ATTRIBUTE)
				.append("=\"")
				.append(ordinal)
				.append("\" style=\"white-space: pre-wrap; font-family: monospace; margin: 0 0 1em\">")
				.append(Entities.escape(paragraph.stripTrailing().replaceFirst("^\\s*\\n", "")))
				.append("</div>\n");
		}
		String viewer = """
				<!DOCTYPE html>
				<html lang="en"><head><meta charset="utf-8"><meta name="referrer" content="no-referrer">
				<style>body { margin: 24px; background: #fff; color: #111; }
				[data-fl-highlight] { background: #fef08a !important; outline: 2px solid #eab308; }</style>
				</head><body>%s</body></html>
				""".formatted(html);
		return new ParseResult(List.copyOf(blocks), viewer);
	}

	private static BlockType classify(String raw, String normalized) {
		boolean multiLineColumns = raw.lines().filter(line -> line.contains("   ")).count() >= 3;
		if (multiLineColumns) {
			return BlockType.TABLE;
		}
		if (normalized.length() <= 120 && (SectionDetector.looksLikeHeading(normalized)
				|| normalized.chars().filter(Character::isLetter).allMatch(Character::isUpperCase))) {
			return BlockType.HEADING;
		}
		return BlockType.PARAGRAPH;
	}

}
