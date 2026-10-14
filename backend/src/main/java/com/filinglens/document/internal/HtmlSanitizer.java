package com.filinglens.document.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * Produces the viewer copy of a filing: an allow-listed subset of HTML that keeps
 * layout (tables, inline styles) and {@code data-fl-block} anchors but no
 * scripts, event handlers, forms, frames, external stylesheets or CSS that loads
 * resources. Relative links and images are made absolute to the filing's sec.gov
 * folder; in-page {@code #} links are kept. The viewer is additionally served
 * with a sandboxing Content-Security-Policy.
 */
final class HtmlSanitizer {

	private static final String VIEWER_STYLE = """
			body { margin: 24px; font-family: Georgia, 'Times New Roman', serif; color: #111; background: #fff; }
			[data-fl-highlight] { background: #fef08a !important; outline: 2px solid #eab308; outline-offset: 2px; }
			""";

	private static final Safelist SAFELIST = Safelist.relaxed()
		.addTags("font", "center", "span", "div", "hr", "sup", "sub", "u", "s", "small", "big", "section", "article",
				"header", "footer", "caption", "colgroup", "col")
		.addAttributes(":all", "style", HtmlDocumentParser.BLOCK_ATTRIBUTE, "id", "align", "valign", "width",
				"height", "title", "dir", "lang")
		.addAttributes("table", "border", "cellpadding", "cellspacing", "bgcolor")
		.addAttributes("td", "bgcolor", "nowrap")
		.addAttributes("th", "bgcolor", "nowrap")
		.addAttributes("font", "color", "size", "face")
		.addAttributes("a", "name")
		.addProtocols("a", "href", "#")
		// Links are made absolute below, so only in-page "#" links stay relative.
		.preserveRelativeLinks(true);

	private HtmlSanitizer() {
	}

	/** @param document parsed with the filing's archive folder as base URI */
	static String toViewerHtml(Document document) {
		absolutize(document, "a[href]", "href");
		absolutize(document, "img[src]", "src");
		Document clean = new Cleaner(SAFELIST).clean(document);
		for (Element element : clean.select("[style]")) {
			String style = safeStyle(element.attr("style"));
			if (style.isEmpty()) {
				element.removeAttr("style");
			}
			else {
				element.attr("style", style);
			}
		}
		for (Element link : clean.select("a[href]")) {
			if (!link.attr("href").startsWith("#")) {
				link.attr("target", "_blank").attr("rel", "noopener noreferrer");
			}
		}
		return """
				<!DOCTYPE html>
				<html lang="en"><head><meta charset="utf-8"><meta name="referrer" content="no-referrer">
				<style>%s</style></head><body>%s</body></html>
				""".formatted(VIEWER_STYLE, clean.body().html());
	}

	/**
	 * Resolves relative URLs against the filing's sec.gov folder. Fragment-only links
	 * stay as they are; unresolvable values are removed. The cleaner then rejects
	 * any non-http(s) scheme such as {@code javascript:}.
	 */
	private static void absolutize(Document document, String selector, String attribute) {
		for (Element element : document.select(selector)) {
			String value = element.attr(attribute).strip();
			if (value.startsWith("#")) {
				continue;
			}
			String absolute = element.absUrl(attribute);
			if (absolute.isEmpty()) {
				element.removeAttr(attribute);
			}
			else {
				element.attr(attribute, absolute);
			}
		}
	}

	/** Drops CSS declarations that could load resources, run code or overlay the page. */
	static String safeStyle(String style) {
		return Arrays.stream(style.split(";"))
			.map(String::strip)
			.filter(declaration -> !declaration.isEmpty())
			.filter(declaration -> {
				String lower = declaration.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
				return !(lower.contains("url(") || lower.contains("expression(") || lower.contains("@import")
						|| lower.contains("javascript:") || lower.startsWith("behavior:")
						|| lower.contains("-moz-binding") || lower.equals("position:fixed")
						|| lower.contains("\\"));
			})
			.collect(Collectors.joining("; "));
	}

}
