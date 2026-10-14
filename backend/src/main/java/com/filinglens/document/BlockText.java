package com.filinglens.document;

/**
 * A text block of a parsed filing.
 *
 * @param ordinal position in the document (viewer anchor)
 * @param ref stable citation reference, e.g. {@code 10K.ITEM_1A/p12}
 * @param type HEADING, PARAGRAPH, LIST_ITEM or TABLE
 */
public record BlockText(int ordinal, String ref, String type, String text, String contentHash) {
}
