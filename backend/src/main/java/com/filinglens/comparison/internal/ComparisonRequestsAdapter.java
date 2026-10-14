package com.filinglens.comparison.internal;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.filinglens.comparison.ComparisonRequests;
import com.filinglens.filing.FilingCatalog;

@Component
class ComparisonRequestsAdapter implements ComparisonRequests {

	private final ComparisonService comparisons;

	private final FilingCatalog filings;

	ComparisonRequestsAdapter(ComparisonService comparisons, FilingCatalog filings) {
		this.comparisons = comparisons;
		this.filings = filings;
	}

	@Override
	public Optional<Long> compareWithPrevious(long filingId) {
		return filings.findById(filingId).flatMap(filing -> {
			String previous = comparisons.candidates(filing.accessionNumber()).suggested();
			return previous == null ? Optional.empty()
					: Optional.of(comparisons.create(previous, filing.accessionNumber()).id());
		});
	}

}
