package com.filinglens.document;

import java.util.List;

/** A section of a parsed filing with its blocks in document order. */
public record SectionText(String key, String title, List<BlockText> blocks) {
}
