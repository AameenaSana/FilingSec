package com.filinglens.comparison.internal;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.comparison.internal.ComparisonViews.Candidates;
import com.filinglens.comparison.internal.ComparisonViews.ChangeView;
import com.filinglens.comparison.internal.ComparisonViews.ComparisonView;
import com.filinglens.comparison.internal.InterpretationService.InterpretationView;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.ratelimit.RateLimiter;

@RestController
class ComparisonController {

	private static final String ACCESSION = "\\d{10}-?\\d{2}-?\\d{6}";

	/** Creating a comparison may download and parse two filings. */
	static final RateLimiter.Limit COMPARISONS = new RateLimiter.Limit("comparisons", 20, Duration.ofMinutes(1));

	static final RateLimiter.Limit INTERPRETATIONS = new RateLimiter.Limit("interpretations", 10, Duration.ofMinutes(1));

	private final ComparisonService comparisons;

	private final InterpretationService interpretations;

	private final RateLimiter rateLimiter;

	ComparisonController(ComparisonService comparisons, InterpretationService interpretations, RateLimiter rateLimiter) {
		this.comparisons = comparisons;
		this.interpretations = interpretations;
		this.rateLimiter = rateLimiter;
	}

	record CreateRequest(@NotNull @Pattern(regexp = ACCESSION) String base,
			@NotNull @Pattern(regexp = ACCESSION) String target) {
	}

	/** Compares two filings; returns the existing comparison if already requested. */
	@PostMapping("/api/v1/comparisons")
	@ResponseStatus(HttpStatus.CREATED)
	ComparisonView create(@Valid @RequestBody CreateRequest body, @AuthenticationPrincipal UserPrincipal user) {
		rateLimiter.acquireOrThrow(COMPARISONS, user.getName());
		return comparisons.create(body.base(), body.target());
	}

	@GetMapping("/api/v1/comparisons/{id}")
	ComparisonView get(@PathVariable long id) {
		return comparisons.get(id);
	}

	/**
	 * Changes in one section, in the later filing's order.
	 * @param includeNumericOnly whether to include changes that differ only in numbers
	 */
	@GetMapping("/api/v1/comparisons/{id}/sections/{key}/changes")
	List<ChangeView> changes(@PathVariable long id,
			@PathVariable @Pattern(regexp = "[A-Z0-9_.]{1,40}") String key,
			@RequestParam(defaultValue = "true") boolean includeNumericOnly) {
		return comparisons.changes(id, key, includeNumericOnly);
	}

	/**
	 * The AI interpretation of a section's changes, if one has been requested. It is
	 * generated from the factual changes and kept separate from them.
	 */
	@GetMapping("/api/v1/comparisons/{id}/sections/{key}/interpretation")
	ResponseEntity<InterpretationView> interpretation(@PathVariable long id,
			@PathVariable @Pattern(regexp = "[A-Z0-9_.]{1,40}") String key) {
		return interpretations.get(id, key).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** Requests an AI interpretation of a section's changes (generated in the background). */
	@PostMapping("/api/v1/comparisons/{id}/sections/{key}/interpretation")
	@ResponseStatus(HttpStatus.ACCEPTED)
	InterpretationView requestInterpretation(@PathVariable long id,
			@PathVariable @Pattern(regexp = "[A-Z0-9_.]{1,40}") String key, @AuthenticationPrincipal UserPrincipal user) {
		rateLimiter.acquireOrThrow(INTERPRETATIONS, user.getName());
		return interpretations.request(user.id(), id, key);
	}

	@GetMapping("/api/v1/filings/{accession}/comparison-candidates")
	Candidates candidates(@PathVariable @Pattern(regexp = ACCESSION) String accession) {
		return comparisons.candidates(accession);
	}

}
