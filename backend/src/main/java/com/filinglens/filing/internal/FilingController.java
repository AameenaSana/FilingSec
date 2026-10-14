package com.filinglens.filing.internal;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.filing.internal.FilingViews.FilingDetail;
import com.filinglens.filing.internal.FilingViews.FilingPage;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.ratelimit.RateLimiter;

@RestController
class FilingController {

	private static final String ACCESSION = "\\d{10}-?\\d{2}-?\\d{6}";

	/** Filing lists and fetches may call SEC; limits keep one user from using the shared SEC budget. */
	static final RateLimiter.Limit FILING_LISTS = new RateLimiter.Limit("filing-lists", 60, Duration.ofMinutes(1));

	static final RateLimiter.Limit FILING_FETCHES = new RateLimiter.Limit("filing-fetches", 30, Duration.ofMinutes(1));

	private final FilingDiscoveryService discovery;

	private final FilingService filings;

	private final RateLimiter rateLimiter;

	FilingController(FilingDiscoveryService discovery, FilingService filings, RateLimiter rateLimiter) {
		this.discovery = discovery;
		this.filings = filings;
		this.rateLimiter = rateLimiter;
	}

	/**
	 * A company's 10-K, 10-Q and 8-K filings (with amendments), newest first.
	 * @param forms base forms to include; all when omitted
	 */
	@GetMapping("/api/v1/companies/{cik}/filings")
	FilingPage list(@PathVariable @Pattern(regexp = "\\d{1,10}", message = "must be a CIK of 1-10 digits") String cik,
			@RequestParam(required = false) @Size(max = 3) List<@Pattern(regexp = "10-K|10-Q|8-K") String> forms,
			@RequestParam(required = false) @Size(max = 64) String cursor,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
			@AuthenticationPrincipal UserPrincipal user) {
		rateLimiter.acquireOrThrow(FILING_LISTS, user.getName());
		return discovery.list(cik, forms, cursor, limit);
	}

	@GetMapping("/api/v1/filings/{accession}")
	FilingDetail get(@PathVariable @Pattern(regexp = ACCESSION, message = "must be an SEC accession number")
	String accession) {
		return filings.get(accession);
	}

	/** Queues download of the filing's documents from SEC. */
	@PostMapping("/api/v1/filings/{accession}/fetch")
	@ResponseStatus(HttpStatus.ACCEPTED)
	FilingDetail fetch(@PathVariable @Pattern(regexp = ACCESSION, message = "must be an SEC accession number")
	String accession, @AuthenticationPrincipal UserPrincipal user) {
		rateLimiter.acquireOrThrow(FILING_FETCHES, user.getName());
		return filings.requestFetch(accession);
	}

}
