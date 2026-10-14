package com.filinglens.research.internal;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.filinglens.identity.UserPrincipal;
import com.filinglens.platform.ratelimit.RateLimiter;
import com.filinglens.research.internal.ResearchViews.ConversationSummary;
import com.filinglens.research.internal.ResearchViews.ConversationView;

@RestController
@RequestMapping("/api/v1/conversations")
class ResearchController {

	private static final String ACCESSION = "\\d{10}-?\\d{2}-?\\d{6}";

	static final RateLimiter.Limit QUESTIONS = new RateLimiter.Limit("questions", 20, Duration.ofMinutes(1));

	/** New conversations index filings and may call the embedding API. */
	static final RateLimiter.Limit CONVERSATIONS = new RateLimiter.Limit("conversations", 10, Duration.ofMinutes(1));

	/** Each cross-company request may read several companies' filings from SEC. */
	static final RateLimiter.Limit CROSS_COMPANY = new RateLimiter.Limit("cross-company", 5, Duration.ofMinutes(1));

	private final ResearchService research;

	private final RateLimiter rateLimiter;

	ResearchController(ResearchService research, RateLimiter rateLimiter) {
		this.research = research;
		this.rateLimiter = rateLimiter;
	}

	record CreateRequest(
			@NotEmpty @Size(max = ResearchService.MAX_FILINGS) List<@Pattern(regexp = ACCESSION) String> filings,
			@Size(max = 200) String title) {
	}

	record CrossCompanyRequest(@NotEmpty @Size(min = 2, max = 4) List<@Pattern(regexp = "\\d{1,10}") String> ciks,
			@NotBlank @Pattern(regexp = "10-K|10-Q|8-K") String formType, @Min(1994) @Max(2100) Integer fiscalYear,
			@Size(max = 120) String topic) {
	}

	record RenameRequest(@NotBlank @Size(max = 200) String title) {
	}

	record QuestionRequest(@NotBlank @Size(max = 2000) String content) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	ConversationView create(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody CreateRequest body) {
		rateLimiter.acquireOrThrow(CONVERSATIONS, user.getName());
		return research.create(user.id(), body.filings(), body.title());
	}

	/**
	 * Starts a cross-company comparison: each company's latest filing of the form
	 * (for the fiscal year, if given) becomes the conversation's scope.
	 */
	@PostMapping("/cross-company")
	@ResponseStatus(HttpStatus.CREATED)
	ConversationView createCrossCompany(@AuthenticationPrincipal UserPrincipal user,
			@Valid @RequestBody CrossCompanyRequest body) {
		rateLimiter.acquireOrThrow(CROSS_COMPANY, user.getName());
		return research.createCrossCompany(user.id(), body.ciks(), body.formType(), body.fiscalYear(), body.topic());
	}

	@GetMapping
	List<ConversationSummary> list(@AuthenticationPrincipal UserPrincipal user) {
		return research.list(user.id());
	}

	@GetMapping("/{id}")
	ConversationView get(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		return research.get(user.id(), id);
	}

	@PatchMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void rename(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
			@Valid @RequestBody RenameRequest body) {
		research.rename(user.id(), id, body.title());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		research.delete(user.id(), id);
	}

	/**
	 * Asks a question and streams the answer as server-sent events: {@code start}
	 * (message ids), {@code delta} (answer text), then {@code done} (the stored
	 * answer with citations) or {@code error}.
	 */
	@PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter ask(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
			@Valid @RequestBody QuestionRequest body, HttpServletResponse response) {
		rateLimiter.acquireOrThrow(QUESTIONS, user.getName());
		SseEmitter emitter = research.ask(user.id(), id, body.content());
		// Stop proxies and compression from buffering the event stream.
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setHeader("X-Accel-Buffering", "no");
		return emitter;
	}

}
