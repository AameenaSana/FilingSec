package com.filinglens.company.internal;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.company.internal.CompanyViews.CompanyDetail;
import com.filinglens.company.internal.CompanyViews.CompanySummary;
import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.ratelimit.RateLimiter;

@RestController
@RequestMapping("/api/v1/companies")
class CompanyController {

	/** Company views may call SEC; this keeps one user from consuming the shared SEC budget. */
	static final RateLimiter.Limit COMPANY_VIEWS = new RateLimiter.Limit("company-views", 60, Duration.ofMinutes(1));

	private final CompanyService companies;

	private final RateLimiter rateLimiter;

	CompanyController(CompanyService companies, RateLimiter rateLimiter) {
		this.companies = companies;
		this.rateLimiter = rateLimiter;
	}

	/** Search by name, ticker or CIK. */
	@GetMapping
	List<CompanySummary> search(@RequestParam @NotBlank @Size(max = 100) String q,
			@RequestParam(defaultValue = "10") @Min(1) @Max(25) int limit) {
		return companies.search(q, limit);
	}

	@GetMapping("/{cik}")
	CompanyDetail get(@PathVariable @Pattern(regexp = "\\d{1,10}", message = "must be a CIK of 1-10 digits") String cik,
			@AuthenticationPrincipal UserPrincipal user) {
		rateLimiter.acquireOrThrow(COMPANY_VIEWS, user.getName());
		return companies.get(cik, user.id());
	}

}
