package com.filinglens.comparison.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import com.filinglens.comparison.internal.ComparisonRepository.ComparisonRow;
import com.filinglens.comparison.internal.ComparisonViews.Candidates;
import com.filinglens.comparison.internal.ComparisonViews.ChangeView;
import com.filinglens.comparison.internal.ComparisonViews.CompanyInfo;
import com.filinglens.comparison.internal.ComparisonViews.ComparisonView;
import com.filinglens.comparison.internal.ComparisonViews.FilingInfo;
import com.filinglens.comparison.internal.ComparisonViews.Status;
import com.filinglens.document.DocumentContent;
import com.filinglens.document.DocumentContent.State;
import com.filinglens.document.DocumentParsed;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.error.InvalidRequestException;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.platform.jobs.JobQueue;

/**
 * Creates comparisons and drives them through their lifecycle:
 * WAITING (documents being downloaded/parsed) → COMPUTING → READY, or FAILED if a
 * document cannot be read. A comparison of the same two filings is computed once
 * and shared.
 */
@Service
class ComparisonService {

	private final ComparisonRepository comparisons;

	private final FilingCatalog filings;

	private final DocumentContent documents;

	private final JobQueue jobs;

	private final Clock clock;

	ComparisonService(ComparisonRepository comparisons, FilingCatalog filings, DocumentContent documents, JobQueue jobs,
			Clock clock) {
		this.comparisons = comparisons;
		this.filings = filings;
		this.documents = documents;
		this.jobs = jobs;
		this.clock = clock;
	}

	/**
	 * Compares two filings of the same company and form; the earlier one becomes
	 * the base regardless of argument order.
	 */
	ComparisonView create(String firstAccession, String secondAccession) {
		FilingRef first = filing(firstAccession);
		FilingRef second = filing(secondAccession);
		if (first.id() == second.id()) {
			throw new InvalidRequestException("same-filing", "Choose two different filings to compare");
		}
		if (first.companyId() != second.companyId() || !first.baseForm().equals(second.baseForm())) {
			throw new InvalidRequestException("not-comparable",
					"Only filings of the same company and form type can be compared");
		}
		boolean firstIsEarlier = period(first).compareTo(period(second)) <= 0;
		FilingRef base = firstIsEarlier ? first : second;
		FilingRef target = firstIsEarlier ? second : first;

		long id = comparisons.findOrCreate(base.id(), target.id(), ComparisonEngine.ALGORITHM_VERSION, clock.instant());
		// A failed comparison is retried when requested again.
		comparisons.transition(id, Status.FAILED, Status.WAITING, null, clock.instant());
		advance(id);
		return view(id);
	}

	ComparisonView get(long id) {
		ComparisonRow row = row(id);
		if (row.status() == Status.WAITING) {
			advance(id);
		}
		return view(id);
	}

	List<ChangeView> changes(long id, String sectionKey, boolean includeNumericOnly) {
		row(id);
		return comparisons.changes(id, sectionKey, includeNumericOnly)
			.orElseThrow(() -> new NotFoundException("Section", sectionKey));
	}

	/**
	 * Filings this one can be compared with. The suggestion is the previous annual
	 * report for a 10-K, the same quarter a year earlier (else the previous quarter)
	 * for a 10-Q, and the previous filing for an 8-K.
	 */
	Candidates candidates(String accession) {
		FilingRef filing = filing(accession);
		List<FilingRef> others = filings.comparableFilings(filing.id(), 24);
		LocalDate period = period(filing);
		List<FilingRef> earlier = others.stream()
			.filter(f -> period(f).isBefore(period))
			.sorted(Comparator.comparing(ComparisonService::period).reversed())
			.toList();
		Optional<FilingRef> suggested = Optional.empty();
		if (filing.baseForm().equals("10-Q")) {
			suggested = earlier.stream().filter(f -> {
				long days = ChronoUnit.DAYS.between(period(f), period);
				return days >= 340 && days <= 390;
			}).findFirst();
		}
		if (suggested.isEmpty()) {
			// Prefer an original over an amendment for the same period.
			suggested = earlier.stream().filter(f -> !f.formType().endsWith("/A")).findFirst()
				.or(() -> earlier.stream().findFirst());
		}
		return new Candidates(suggested.map(FilingRef::accessionNumber).orElse(null),
				others.stream().map(ComparisonService::info).toList());
	}

	/** Re-evaluates comparisons waiting on a filing once its document is parsed. */
	@ApplicationModuleListener
	void on(DocumentParsed event) {
		comparisons.waitingFor(event.filingId()).forEach(this::advance);
	}

	/**
	 * Starts computing if both documents are parsed, fails if either cannot be read,
	 * and otherwise makes sure both are being prepared.
	 */
	void advance(long id) {
		ComparisonRow row = row(id);
		if (row.status() != Status.WAITING) {
			return;
		}
		State base = documents.state(row.baseFilingId());
		State target = documents.state(row.targetFilingId());
		if (base == State.UNAVAILABLE || target == State.UNAVAILABLE) {
			comparisons.transition(id, Status.WAITING, Status.FAILED,
					"The %s filing's document could not be downloaded or read"
						.formatted(base == State.UNAVAILABLE ? "earlier" : "later"),
					clock.instant());
			return;
		}
		if (base == State.PARSED && target == State.PARSED) {
			if (comparisons.transition(id, Status.WAITING, Status.COMPUTING, null, clock.instant())) {
				jobs.enqueueUnique(ComparisonJob.TYPE, Map.of("comparisonId", id), ComparisonJob.TYPE + ":" + id);
			}
			return;
		}
		if (base != State.PARSED) {
			documents.prepare(row.baseFilingId());
		}
		if (target != State.PARSED) {
			documents.prepare(row.targetFilingId());
		}
	}

	private ComparisonView view(long id) {
		ComparisonRow row = row(id);
		FilingRef base = filings.findById(row.baseFilingId()).orElseThrow();
		FilingRef target = filings.findById(row.targetFilingId()).orElseThrow();
		return new ComparisonView(id, row.status(), row.detail(), new CompanyInfo(base.cik(), base.companyName()),
				info(base), info(target), row.stats(), row.computedAt(),
				row.status() == Status.READY ? comparisons.sections(id) : List.of());
	}

	private ComparisonRow row(long id) {
		return comparisons.find(id).orElseThrow(() -> new NotFoundException("Comparison", id));
	}

	private FilingRef filing(String accession) {
		return filings.findByAccession(accession).orElseThrow(() -> new NotFoundException("Filing", accession));
	}

	private static FilingInfo info(FilingRef filing) {
		return new FilingInfo(filing.accessionNumber(), filing.formType(), filing.filingDate(), filing.reportDate());
	}

	/** Period of report, or filing date when a form has none. */
	static LocalDate period(FilingRef filing) {
		return filing.reportDate() != null ? filing.reportDate() : filing.filingDate();
	}

}
