package com.filinglens.document.internal;

import java.util.List;

/** @param annotatedHtml sanitized HTML of the whole document with {@code data-fl-block} anchors */
record ParseResult(List<ParsedBlock> blocks, String annotatedHtml) {
}
