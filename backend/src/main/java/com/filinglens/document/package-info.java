/**
 * Document processing: turns a fetched filing's original document (HTML, inline
 * XBRL or plain text) into form-aware sections and stable, citable text blocks,
 * and produces a sanitized, annotated copy for the in-app source viewer.
 * <p>
 * Public API: the {@link com.filinglens.document.DocumentParsed} event.
 */
@ApplicationModule(displayName = "Document processing")
package com.filinglens.document;

import org.springframework.modulith.ApplicationModule;
