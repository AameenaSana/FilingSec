package com.filinglens.document;

/** A block together with the section it belongs to. */
public record LocatedBlock(BlockText block, String sectionKey, String sectionTitle) {
}
