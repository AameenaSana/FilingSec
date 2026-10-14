/**
 * Period-over-period comparison of a company's filings: aligned sections and the
 * exact textual changes between them (added, removed, modified, moved), each tied
 * to the blocks it came from so it can be located in both source documents.
 * <p>
 * Results are factual differences only. Interpretations of their significance
 * are produced and stored separately.
 * <p>
 * Public API: the {@link com.filinglens.comparison.ComparisonCompleted} event.
 */
@ApplicationModule(displayName = "Comparison")
package com.filinglens.comparison;

import org.springframework.modulith.ApplicationModule;
