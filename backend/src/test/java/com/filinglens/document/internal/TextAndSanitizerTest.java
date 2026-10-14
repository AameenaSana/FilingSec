package com.filinglens.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;

import org.junit.jupiter.api.Test;

class TextAndSanitizerTest {

	@Test
	void parsesPlainTextFilingsIntoParagraphs() {
		String filing = """
				<DOCUMENT>
				<TYPE>10-K
				<TEXT>
				                         ANNUAL REPORT

				Item 1.  Business

				The Company   makes widgets
				and sells them worldwide.

				                               12
				<PAGE>
				                           2000       1999
				Net sales               $1,234     $1,100
				Net income                 120        100
				</TEXT>
				</DOCUMENT>
				""";

		ParseResult result = TextDocumentParser.parse(filing);

		assertThat(result.blocks()).extracting(ParsedBlock::text)
			.containsExactly("ANNUAL REPORT", "Item 1. Business", "The Company makes widgets and sells them worldwide.",
					"2000 1999 Net sales $1,234 $1,100 Net income 120 100");
		assertThat(result.blocks().get(1).type()).isEqualTo(BlockType.HEADING);
		assertThat(result.blocks().get(3).type()).isEqualTo(BlockType.TABLE);
		assertThat(result.annotatedHtml()).contains("data-fl-block=\"4\"").contains("Net sales               $1,234");
		assertThat(result.annotatedHtml()).doesNotContain("<PAGE>").doesNotContain("<TEXT>");
	}

	@Test
	void plainTextViewerEscapesMarkup() {
		ParseResult result = TextDocumentParser.parse("Risk: <script>alert(1)</script> & more");

		assertThat(result.annotatedHtml()).contains("&lt;script&gt;").doesNotContain("<script>");
	}

	@Test
	void unsafeCssDeclarationsAreDropped() {
		assertThat(HtmlSanitizer.safeStyle("color:red; background: URL(x.png); font-weight:700"))
			.isEqualTo("color:red; font-weight:700");
		assertThat(HtmlSanitizer.safeStyle("width: expression(alert(1)); position: fixed; top:0")).isEqualTo("top:0");
		assertThat(HtmlSanitizer.safeStyle("background: u\\72l(x)")).isEmpty();
	}

	@Test
	void decodesDeclaredCharsetUtf8OrWindows1252() {
		byte[] cp1252 = "Company’s “products”".getBytes(Charset.forName("windows-1252"));
		assertThat(DocumentParseJob.decode(cp1252, true)).isEqualTo("Company’s “products”");
		assertThat(DocumentParseJob.decode("café".getBytes(java.nio.charset.StandardCharsets.UTF_8), true))
			.isEqualTo("café");
		byte[] latin1Declared = "<meta charset=\"iso-8859-1\">café".getBytes(Charset.forName("iso-8859-1"));
		assertThat(DocumentParseJob.decode(latin1Declared, true)).endsWith("café");
	}

	@Test
	void pageArtifactsAreRecognized() {
		assertThat(TextNormalizer.isPageArtifact("12")).isTrue();
		assertThat(TextNormalizer.isPageArtifact("Page 7")).isTrue();
		assertThat(TextNormalizer.isPageArtifact("iv")).isTrue();
		assertThat(TextNormalizer.isPageArtifact("Apple Inc. | 2025 Form 10-K | 21")).isTrue();
		assertThat(TextNormalizer.isPageArtifact("Net sales increased 12%")).isFalse();
		assertThat(TextNormalizer.isPageArtifact("2025")).isFalse();
	}

}
