/**
 * Filings: discovery of 10-K, 10-Q and 8-K filings (and amendments) from EDGAR,
 * filing metadata, document lists, and fetching of original documents into the
 * blob store.
 * <p>
 * Public API: {@link com.filinglens.filing.FilingCatalog} for lookups and the
 * {@link com.filinglens.filing.FilingFetched} event, published when a filing's
 * primary document has been stored.
 */
@ApplicationModule(displayName = "Filings")
package com.filinglens.filing;

import org.springframework.modulith.ApplicationModule;
