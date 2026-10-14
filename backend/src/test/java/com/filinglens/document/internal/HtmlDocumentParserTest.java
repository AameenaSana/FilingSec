package com.filinglens.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.filinglens.document.internal.SectionDetector.Confidence;
import com.filinglens.document.internal.SectionDetector.DetectedSection;

class HtmlDocumentParserTest {

	static final String BASE = "https://www.sec.gov/Archives/edgar/data/1234567/000123456725000001/";

	static ParseResult result;

	static List<ParsedBlock> blocks;

	@BeforeAll
	static void parse() throws IOException {
		try (InputStream in = HtmlDocumentParserTest.class.getResourceAsStream("/documents/sample-10k.htm")) {
			result = HtmlDocumentParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), BASE);
		}
		blocks = result.blocks();
	}

	@Test
	void dropsScriptsHiddenXbrlAndPageFurniture() {
		assertThat(blocks).extracting(ParsedBlock::text)
			.noneMatch(t -> t.contains("secret script text"))
			.noneMatch(t -> t.contains("Hidden XBRL context"))
			.noneMatch(t -> t.equals("2"))
			.noneMatch(t -> t.contains("2025 Form 10-K | 1"))
			.noneMatch(t -> t.equalsIgnoreCase("Table of Contents"));
	}

	@Test
	void unwrapsInlineXbrlSoTaggedFactsReadAsText() {
		assertThat(text("For the fiscal year ended")).isEqualTo("For the fiscal year ended September 27, 2025");
		assertThat(text("Sample Corp. (Exact")).startsWith("Sample Corp. (Exact name");
	}

	@Test
	void classifiesHeadingsParagraphsListsAndTables() {
		assertThat(block("Item 1. Business", 2).type()).isEqualTo(BlockType.HEADING);
		assertThat(find("Item 1A. Risk Factors").type()).as("single-row heading table").isEqualTo(BlockType.HEADING);
		assertThat(find("Global economic conditions").type()).isEqualTo(BlockType.LIST_ITEM);
		assertThat(find("Sample Corp. designs").type()).isEqualTo(BlockType.PARAGRAPH);
		ParsedBlock financials = find("Net sales |");
		assertThat(financials.type()).isEqualTo(BlockType.TABLE);
		assertThat(financials.text()).isEqualTo("2025 | 2024\nNet sales | $391,035 | $383,285\nGross margin | 180,683 | 169,148");
		assertThat(find("Item 1. | Business | 1").type()).as("table of contents table").isEqualTo(BlockType.TABLE);
	}

	@Test
	void splitsMixedInlineAndBlockContent() {
		assertThat(blocks).extracting(ParsedBlock::text)
			.containsSubsequence("Intro text bold words more text", "Nested block of text inside a container.",
					"tail text after the nested block");
	}

	@Test
	void ordinalsAreSequentialAndAnnotatedInTheViewerCopy() {
		for (int i = 0; i < blocks.size(); i++) {
			assertThat(blocks.get(i).ordinal()).isEqualTo(i + 1);
		}
		Document viewer = Jsoup.parse(result.annotatedHtml());
		assertThat(viewer.select("[data-fl-block]")).hasSize(blocks.size());
		ParsedBlock riskHeading = find("Item 1A. Risk Factors");
		assertThat(viewer.selectFirst("[data-fl-block=" + riskHeading.ordinal() + "]").text())
			.isEqualTo("Item 1A. Risk Factors");
	}

	@Test
	void viewerCopyIsSanitized() {
		String html = result.annotatedHtml();
		Document viewer = Jsoup.parse(html);
		assertThat(html).doesNotContain("<script", "javascript:", "onerror", "url(", "evil.example");
		assertThat(viewer.selectFirst("img").attr("src")).isEqualTo(BASE + "logo.jpg");
		assertThat(viewer.selectFirst("a:contains(Exhibit 21)").attr("href")).isEqualTo(BASE + "ex21.htm");
		assertThat(viewer.selectFirst("a:contains(Exhibit 21)").attr("target")).isEqualTo("_blank");
		assertThat(viewer.selectFirst("a:contains(bad link)").hasAttr("href")).isFalse();
		assertThat(viewer.selectFirst("a[href=#item1]")).as("in-page links kept").isNotNull();
		assertThat(viewer.selectFirst("div:contains(Styled paragraph)").attr("style")).isEqualTo("color: red");
	}

	@Test
	void detectsTenKSectionsSkippingTableOfContentsEntries() {
		List<DetectedSection> sections = SectionDetector.detect("10-K", blocks);

		assertThat(sections).extracting(DetectedSection::key)
			.containsExactly("COVER", "10K.ITEM_1", "10K.ITEM_1A", "10K.ITEM_7", "10K.ITEM_15");
		DetectedSection business = sections.get(1);
		assertThat(business.firstOrdinal()).isEqualTo(block("Item 1. Business", 2).ordinal());
		assertThat(business.title()).isEqualTo("Business");
		assertThat(business.headingText()).isEqualTo("Item 1. Business");
		assertThat(sections.get(2).confidence()).isEqualTo(Confidence.HIGH);
		// Every block belongs to exactly one section, in order.
		for (int i = 1; i < sections.size(); i++) {
			assertThat(sections.get(i).firstOrdinal()).isEqualTo(sections.get(i - 1).lastOrdinal() + 1);
		}
		assertThat(sections.getLast().lastOrdinal()).isEqualTo(blocks.getLast().ordinal());
		// A cross-reference inside a paragraph is not a heading.
		assertThat(find("see Item 7 of this Form 10-K").ordinal()).isBetween(sections.get(2).firstOrdinal(),
				sections.get(2).lastOrdinal());
	}

	private static ParsedBlock find(String fragment) {
		return blocks.stream().filter(b -> b.text().contains(fragment)).findFirst().orElseThrow();
	}

	private static String text(String prefix) {
		return blocks.stream().map(ParsedBlock::text).filter(t -> t.startsWith(prefix)).findFirst().orElseThrow();
	}

	/** The nth block whose text equals the given text (1-based). */
	private static ParsedBlock block(String exactText, int occurrence) {
		Optional<ParsedBlock> found = blocks.stream().filter(b -> b.text().equals(exactText)).skip(occurrence - 1).findFirst();
		return found.orElseThrow();
	}

}
