package com.filinglens.comparison.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.filinglens.comparison.internal.ComparisonEngine.Alignment;
import com.filinglens.comparison.internal.ComparisonEngine.Change;
import com.filinglens.comparison.internal.ComparisonEngine.ChangeType;
import com.filinglens.comparison.internal.ComparisonEngine.SectionResult;
import com.filinglens.document.BlockText;
import com.filinglens.document.SectionText;

class ComparisonEngineTest {

	static final String INTRO = "The Company's business can be affected by a number of factors described below.";

	static final String SUPPLY = "The Company depends on component and product manufacturing performed by outsourcing partners located primarily in Asia.";

	static final String AI_2024 = "The Company is investing in artificial intelligence features, which may not be successful and could affect results.";

	static final String AI_2025 = "The Company is investing heavily in generative artificial intelligence features, which may not be successful and could materially affect results.";

	static final String TARIFFS = "New tariffs and trade restrictions could increase the cost of the Company's products and reduce demand.";

	static final String PANDEMIC = "The COVID-19 pandemic has had, and may continue to have, an adverse effect on the Company's operations.";

	static final String FISCAL_2024 = "During fiscal 2024 the Company repurchased 499 million shares for $95.0 billion.";

	static final String FISCAL_2025 = "During fiscal 2025 the Company repurchased 512 million shares for $90.7 billion.";

	@Test
	void unchangedSectionsHaveNoChanges() {
		var base = List.of(section("10K.ITEM_1A", INTRO, SUPPLY));
		var target = List.of(section("10K.ITEM_1A", INTRO, SUPPLY));

		SectionResult result = ComparisonEngine.compare(base, target).getFirst();

		assertThat(result.alignment()).isEqualTo(Alignment.MATCHED);
		assertThat(result.changes()).isEmpty();
		assertThat(result.unchanged()).isEqualTo(2);
	}

	@Test
	void detectsModifiedAddedAndRemovedParagraphsInTargetOrder() {
		var base = List.of(section("10K.ITEM_1A", INTRO, AI_2024, PANDEMIC, SUPPLY));
		var target = List.of(section("10K.ITEM_1A", INTRO, AI_2025, TARIFFS, SUPPLY));

		SectionResult result = ComparisonEngine.compare(base, target).getFirst();

		assertThat(result.changes()).extracting(Change::type)
			.containsExactly(ChangeType.MODIFIED, ChangeType.REMOVED, ChangeType.ADDED);
		Change modified = result.changes().getFirst();
		assertThat(modified.base().text()).isEqualTo(AI_2024);
		assertThat(modified.target().text()).isEqualTo(AI_2025);
		assertThat(modified.numericOnly()).isFalse();
		assertThat(modified.similarity()).isBetween(0.5, 1.0);
		assertThat(modified.wordDiff()).filteredOn(op -> !op.op().equals("equal"))
			.containsExactly(new WordDiff.Op("insert", "heavily "), new WordDiff.Op("insert", "generative "),
					new WordDiff.Op("insert", "materially "));
		assertThat(result.changes().get(1).base().text()).isEqualTo(PANDEMIC);
		assertThat(result.changes().get(2).target().text()).isEqualTo(TARIFFS);
		assertThat(result.unchanged()).isEqualTo(2);
	}

	@Test
	void flagsChangesThatDifferOnlyInNumbers() {
		var result = ComparisonEngine.compare(List.of(section("10K.ITEM_5", FISCAL_2024)),
				List.of(section("10K.ITEM_5", FISCAL_2025)));

		Change change = result.getFirst().changes().getFirst();
		assertThat(change.type()).isEqualTo(ChangeType.MODIFIED);
		assertThat(change.numericOnly()).isTrue();
	}

	@Test
	void alignsAddedAndRemovedSections() {
		var base = List.of(section("10K.ITEM_1", INTRO), section("10K.ITEM_1B", "None."));
		var target = List.of(section("10K.ITEM_1", INTRO), section("10K.ITEM_1C", SUPPLY, TARIFFS));

		List<SectionResult> results = ComparisonEngine.compare(base, target);

		assertThat(results).extracting(SectionResult::key, SectionResult::alignment)
			.containsExactly(tuple("10K.ITEM_1", Alignment.MATCHED), tuple("10K.ITEM_1C", Alignment.ADDED),
					tuple("10K.ITEM_1B", Alignment.REMOVED));
		assertThat(results.get(1).count(ChangeType.ADDED)).isEqualTo(2);
		assertThat(results.get(2).count(ChangeType.REMOVED)).isEqualTo(1);
	}

	@Test
	void identicalTextRelocatedToAnotherSectionIsMoved() {
		var base = List.of(section("10K.ITEM_1A", INTRO, TARIFFS), section("10K.ITEM_7", SUPPLY));
		var target = List.of(section("10K.ITEM_1A", INTRO), section("10K.ITEM_7", SUPPLY, TARIFFS));

		List<SectionResult> results = ComparisonEngine.compare(base, target);

		assertThat(results.getFirst().changes()).isEmpty();
		Change moved = results.get(1).changes().getFirst();
		assertThat(moved.type()).isEqualTo(ChangeType.MOVED);
		assertThat(moved.base().ref()).isEqualTo("10K.ITEM_1A/p2");
		assertThat(moved.target().ref()).isEqualTo("10K.ITEM_7/p2");
	}

	@Test
	void pairsEachBlockWithItsMostSimilarCounterpart() {
		String a1 = "Revenue from the Americas segment increased due to higher iPhone sales during the year.";
		String b1 = "Revenue from the Europe segment decreased due to lower Mac sales during the year.";
		String a2 = "Revenue from the Americas segment increased due to higher iPhone and services sales during the year.";
		String b2 = "Revenue from the Europe segment decreased due to lower Mac and iPad sales during the year.";

		SectionResult result = ComparisonEngine
			.compare(List.of(section("10K.ITEM_7", a1, b1)), List.of(section("10K.ITEM_7", b2, a2)))
			.getFirst();

		assertThat(result.changes()).allSatisfy(change -> {
			assertThat(change.type()).isEqualTo(ChangeType.MODIFIED);
			assertThat(change.base().text().substring(0, 30)).isEqualTo(change.target().text().substring(0, 30));
		});
	}

	@Test
	void shortDissimilarHeadingsAreNotPaired() {
		SectionResult result = ComparisonEngine
			.compare(List.of(section("10K.ITEM_1A", "Macroeconomic Risks")), List.of(section("10K.ITEM_1A", "Legal Risks")))
			.getFirst();

		assertThat(result.changes()).extracting(Change::type).containsExactly(ChangeType.REMOVED, ChangeType.ADDED);
	}

	static SectionText section(String key, String... texts) {
		List<BlockText> blocks = new ArrayList<>();
		for (String text : texts) {
			int n = blocks.size() + 1;
			blocks.add(new BlockText(n, key + "/p" + n, "PARAGRAPH", text, Integer.toHexString(text.hashCode())));
		}
		return new SectionText(key, key, blocks);
	}

}
