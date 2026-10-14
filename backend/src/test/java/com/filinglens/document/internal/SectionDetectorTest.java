package com.filinglens.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.filinglens.document.internal.SectionDetector.DetectedSection;

class SectionDetectorTest {

	private static final String BODY = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(10);

	@Test
	void tenQItemsAreKeyedByPart() {
		List<ParsedBlock> blocks = blocks("PART I — FINANCIAL INFORMATION", "Item 1. Financial Statements", BODY,
				"Item 2. Management's Discussion and Analysis", BODY, "PART II — OTHER INFORMATION",
				"Item 1. Legal Proceedings", BODY, "Item 1A. Risk Factors", BODY, "Item 6. Exhibits", BODY);

		assertThat(SectionDetector.detect("10-Q", blocks)).extracting(DetectedSection::key)
			.containsExactly("COVER", "10Q.PART_I.ITEM_1", "10Q.PART_I.ITEM_2", "10Q.PART_II.ITEM_1",
					"10Q.PART_II.ITEM_1A", "10Q.PART_II.ITEM_6");
	}

	@Test
	void eightKItemsUseDecimalNumbers() {
		List<ParsedBlock> blocks = blocks("FORM 8-K", "Item 2.02 Results of Operations and Financial Condition.", BODY,
				"Item 9.01. Financial Statements and Exhibits.", BODY);

		List<DetectedSection> sections = SectionDetector.detect("8-K", blocks);

		assertThat(sections).extracting(DetectedSection::key).containsExactly("COVER", "8K.ITEM_2.02", "8K.ITEM_9.01");
		assertThat(sections.get(1).title()).isEqualTo("Results of Operations and Financial Condition");
	}

	@Test
	void amendmentsUseTheBaseFormCatalog() {
		assertThat(SectionDetector.detect("10-K/A", blocks("Item 9A. Controls and Procedures", BODY)))
			.extracting(DetectedSection::key)
			.containsExactly("10K.ITEM_9A");
	}

	@Test
	void documentWithoutHeadingsIsOneSection() {
		List<DetectedSection> sections = SectionDetector.detect("10-K", blocks(BODY, BODY));

		assertThat(sections).singleElement().satisfies(s -> {
			assertThat(s.key()).isEqualTo("DOCUMENT");
			assertThat(s.firstOrdinal()).isEqualTo(1);
			assertThat(s.lastOrdinal()).isEqualTo(2);
		});
	}

	@Test
	void picksThatBreakItemOrderAreDropped() {
		// A stray "Item 1" late in the document must not override the real order.
		List<ParsedBlock> blocks = blocks("Item 1. Business", BODY, "Item 7. MD&A", BODY, BODY,
				"Item 1A. Risk Factors", BODY + BODY + BODY);

		assertThat(SectionDetector.detect("10-K", blocks)).extracting(DetectedSection::key)
			.containsExactly("10K.ITEM_1", "10K.ITEM_7");
	}

	@Test
	void recognizesHeadingVariants() {
		assertThat(SectionDetector.looksLikeHeading("ITEM 7A. QUANTITATIVE AND QUALITATIVE DISCLOSURES")).isTrue();
		assertThat(SectionDetector.looksLikeHeading("Item 7")).isTrue();
		assertThat(SectionDetector.looksLikeHeading("Item 1C — Cybersecurity")).isTrue();
		assertThat(SectionDetector.looksLikeHeading("PART II")).isTrue();
		assertThat(SectionDetector.looksLikeHeading("Items are shipped weekly")).isFalse();
		assertThat(SectionDetector.looksLikeHeading("Itemized deductions")).isFalse();
	}

	private static List<ParsedBlock> blocks(String... texts) {
		List<ParsedBlock> blocks = new ArrayList<>();
		for (String text : texts) {
			BlockType type = text.length() < 120 ? BlockType.HEADING : BlockType.PARAGRAPH;
			blocks.add(new ParsedBlock(blocks.size() + 1, type, text));
		}
		return blocks;
	}

}
