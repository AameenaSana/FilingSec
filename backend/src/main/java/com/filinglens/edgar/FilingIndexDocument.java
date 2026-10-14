package com.filinglens.edgar;

/**
 * A document listed on a filing's index page.
 *
 * @param sequence position within the filing; the primary document is 1
 * @param type SEC document type, e.g. {@code 10-K}, {@code EX-21.1}, {@code GRAPHIC}
 * @param sizeBytes may be {@code null} if SEC does not report it
 */
public record FilingIndexDocument(int sequence, String description, String filename, String type, Long sizeBytes) {
}
