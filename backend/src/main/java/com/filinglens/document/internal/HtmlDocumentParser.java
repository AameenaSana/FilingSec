package com.filinglens.document.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * Extracts text blocks from filing HTML, including inline XBRL.
 * <ul>
 * <li>Removes scripts, styles, hidden content and the inline XBRL header, and
 * unwraps {@code ix:*} tags so tagged facts read as ordinary text.</li>
 * <li>Emits one block per leaf block element; inline content mixed with block
 * siblings is wrapped in a {@code span} and emitted as its own block.</li>
 * <li>Tables: a table with one non-empty row is a paragraph (often a heading laid
 * out as "Item 7. | Management's Discussion..."), a table whose rows each have at
 * most one non-empty cell is layout and is descended into, and anything else is
 * a {@link BlockType#TABLE} block.</li>
 * <li>Drops page numbers, running headers and "Table of Contents" back-links.</li>
 * </ul>
 * Each emitted element is annotated with {@code data-fl-block="<ordinal>"} so the
 * viewer can locate it.
 */
final class HtmlDocumentParser {

	static final String BLOCK_ATTRIBUTE = "data-fl-block";

	private static final Set<String> BLOCK_TAGS = Set.of("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
			"blockquote", "pre", "center", "section", "article", "dd", "dt", "address", "ul", "ol", "dl", "body",
			"header", "footer", "main", "nav", "aside", "figure", "figcaption", "form", "fieldset", "tr", "td", "th",
			"tbody", "thead", "tfoot", "caption", "hr");

	private static final String BLOCK_SELECTOR = String.join(",", BLOCK_TAGS) + ",table";

	private static final Pattern HIDDEN_STYLE = Pattern.compile("(?i)display\\s*:\\s*none");

	private static final Pattern BOLD_STYLE = Pattern.compile("(?i)font-weight\\s*:\\s*(bold|bolder|[6-9]00)");

	private static final Pattern BULLET = Pattern.compile("^[\\u2022\\u25CF\\u25E6\\u25AA\\u2023\\u2043\\u00B7]\\s*.*");

	private final List<ParsedBlock> blocks = new ArrayList<>();

	private HtmlDocumentParser() {
	}

	/** @param baseUri the filing's archive folder URL, used to absolutize links in the annotated copy */
	static ParseResult parse(String html, String baseUri) {
		Document document = Jsoup.parse(html, baseUri);
		removeNonContent(document);
		HtmlDocumentParser parser = new HtmlDocumentParser();
		parser.walk(document.body());
		return new ParseResult(List.copyOf(parser.blocks), HtmlSanitizer.toViewerHtml(document));
	}

	private static void removeNonContent(Document document) {
		document.select("script, style, noscript, template, iframe, object, embed, link, meta, title").remove();
		for (Element element : new ArrayList<>(document.getAllElements())) {
			String tag = element.tagName().toLowerCase(Locale.ROOT);
			if (tag.equals("ix:header") || HIDDEN_STYLE.matcher(element.attr("style")).find()) {
				element.remove();
			}
		}
		for (Element element : new ArrayList<>(document.getAllElements())) {
			if (element.tagName().toLowerCase(Locale.ROOT).startsWith("ix:")) {
				element.unwrap();
			}
		}
	}

	private void walk(Element element) {
		if (element.tagName().equals("table")) {
			table(element);
			return;
		}
		if (isBlock(element) && !hasBlockDescendant(element)) {
			emit(element, element.text());
			return;
		}
		List<Node> run = new ArrayList<>();
		for (Node child : new ArrayList<>(element.childNodes())) {
			if (child instanceof Element childElement
					&& (childElement.tagName().equals("table") || isBlock(childElement)
							|| hasBlockDescendant(childElement))) {
				flush(run);
				if (!childElement.tagName().equals("hr")) {
					walk(childElement);
				}
			}
			else {
				run.add(child);
			}
		}
		flush(run);
	}

	private void table(Element table) {
		List<List<Element>> rows = new ArrayList<>();
		for (Element row : table.select("> tr, > tbody > tr, > thead > tr, > tfoot > tr")) {
			List<Element> cells = new ArrayList<>();
			for (Element cell : row.select("> td, > th")) {
				if (!TextNormalizer.normalize(cell.text()).isEmpty()) {
					cells.add(cell);
				}
			}
			if (!cells.isEmpty()) {
				rows.add(cells);
			}
		}
		if (rows.isEmpty()) {
			return;
		}
		if (rows.size() == 1) {
			emit(table, String.join(" ", rows.getFirst().stream().map(Element::text).toList()));
			return;
		}
		if (rows.stream().allMatch(cells -> cells.size() == 1)) {
			for (List<Element> cells : rows) {
				walk(cells.getFirst());
			}
			return;
		}
		List<String> lines = new ArrayList<>();
		for (List<Element> cells : rows) {
			lines.add(String.join(" | ", cells.stream().map(c -> TextNormalizer.normalize(c.text())).toList()));
		}
		addBlock(table, BlockType.TABLE, String.join("\n", lines));
	}

	/** Wraps a run of inline nodes in a span and emits it. */
	private void flush(List<Node> run) {
		if (run.isEmpty()) {
			return;
		}
		StringBuilder text = new StringBuilder();
		for (Node node : run) {
			text.append(node instanceof TextNode t ? t.getWholeText() : node instanceof Element e ? e.text() : "")
				.append(' ');
		}
		String normalized = TextNormalizer.normalize(text.toString());
		if (!TextNormalizer.isPageArtifact(normalized)) {
			Element span = new Element("span");
			run.getFirst().before(span);
			for (Node node : run) {
				span.appendChild(node);
			}
			addBlock(span, classify(span, normalized), normalized);
		}
		run.clear();
	}

	private void emit(Element element, String rawText) {
		String text = TextNormalizer.normalize(rawText);
		if (!TextNormalizer.isPageArtifact(text)) {
			addBlock(element, classify(element, text), text);
		}
	}

	private void addBlock(Element element, BlockType type, String text) {
		int ordinal = blocks.size() + 1;
		element.attr(BLOCK_ATTRIBUTE, Integer.toString(ordinal));
		blocks.add(new ParsedBlock(ordinal, type, text));
	}

	static BlockType classify(Element element, String text) {
		String tag = element.tagName();
		if (tag.matches("h[1-6]")) {
			return BlockType.HEADING;
		}
		if (tag.equals("li") || BULLET.matcher(text).matches()) {
			return BlockType.LIST_ITEM;
		}
		if (text.length() <= 200 && (SectionDetector.looksLikeHeading(text) || boldRatio(element) >= 0.9
				|| (text.length() <= 100 && isAllCaps(text)))) {
			return BlockType.HEADING;
		}
		return BlockType.PARAGRAPH;
	}

	/** Share of the element's text that is rendered bold. */
	static double boldRatio(Element element) {
		int total = 0;
		int bold = 0;
		for (TextNode node : collectTextNodes(element)) {
			int length = node.text().strip().length();
			total += length;
			if (isBold(node, element)) {
				bold += length;
			}
		}
		return total == 0 ? 0 : (double) bold / total;
	}

	private static List<TextNode> collectTextNodes(Element root) {
		List<TextNode> nodes = new ArrayList<>();
		root.traverse((node, depth) -> {
			if (node instanceof TextNode text && !text.isBlank()) {
				nodes.add(text);
			}
		});
		return nodes;
	}

	private static boolean isBold(Node node, Element root) {
		for (Node current = node.parent(); current instanceof Element e; current = e.parent()) {
			String tag = e.tagName();
			if (tag.equals("b") || tag.equals("strong") || BOLD_STYLE.matcher(e.attr("style")).find()) {
				return true;
			}
			if (e == root) {
				break;
			}
		}
		return false;
	}

	private static boolean isAllCaps(String text) {
		long letters = text.chars().filter(Character::isLetter).count();
		return letters >= 4 && text.chars().filter(Character::isLetter).allMatch(Character::isUpperCase);
	}

	private static boolean isBlock(Element element) {
		return BLOCK_TAGS.contains(element.tagName());
	}

	private static boolean hasBlockDescendant(Element element) {
		Elements found = element.select(BLOCK_SELECTOR);
		return found.size() > (isBlock(element) || element.tagName().equals("table") ? 1 : 0);
	}

}
