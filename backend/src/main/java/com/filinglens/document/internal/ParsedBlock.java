package com.filinglens.document.internal;

/**
 * A unit of text extracted from a filing.
 *
 * @param ordinal 1-based position in the document; matches {@code data-fl-block}
 * in the annotated HTML
 * @param text normalized text (whitespace collapsed; tables as rows of
 * {@code " | "}-separated cells, one row per line)
 */
record ParsedBlock(int ordinal, BlockType type, String text) {
}
