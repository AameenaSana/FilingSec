package com.filinglens.document;

/** Published when a filing's sections and text blocks have been (re)built. */
public record DocumentParsed(long filingId, String accessionNumber, int parserVersion) {
}
