package com.filinglens.workspace.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.filinglens.identity.UserPrincipal;
import com.filinglens.workspace.internal.WorkspaceService.SourceReference;
import com.filinglens.workspace.internal.WorkspaceViews.FindingView;
import com.filinglens.workspace.internal.WorkspaceViews.SessionSummary;
import com.filinglens.workspace.internal.WorkspaceViews.SessionView;

@RestController
class WorkspaceController {

	private final WorkspaceService workspace;

	WorkspaceController(WorkspaceService workspace) {
		this.workspace = workspace;
	}

	record SessionRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String description) {
	}

	record SourceRequest(@NotNull @Pattern(regexp = "CITATION|CHANGE|PASSAGE") String type, @Positive Long citationId,
			@Positive Long comparisonId, @Pattern(regexp = "[A-Z0-9_.]{1,40}") String sectionKey,
			@Positive Integer changeOrdinal, @Pattern(regexp = "\\d{10}-?\\d{2}-?\\d{6}") String accessionNumber,
			@Positive Integer blockOrdinal) {

		SourceReference toReference() {
			return new SourceReference(type, citationId, comparisonId, sectionKey, changeOrdinal, accessionNumber,
					blockOrdinal);
		}

	}

	record SaveFindingRequest(@Positive Long sessionId, @NotNull @Valid SourceRequest source,
			@Size(max = 5000) String note, @Size(max = 10) List<@Size(max = 40) String> tags) {
	}

	record UpdateFindingRequest(@Positive Long sessionId, @Size(max = 5000) String note,
			@Size(max = 10) List<@Size(max = 40) String> tags) {
	}

	// --- Sessions ----------------------------------------------------------------

	@GetMapping("/api/v1/research-sessions")
	List<SessionSummary> sessions(@AuthenticationPrincipal UserPrincipal user) {
		return workspace.sessions(user.id());
	}

	@PostMapping("/api/v1/research-sessions")
	@ResponseStatus(HttpStatus.CREATED)
	SessionView createSession(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody SessionRequest body) {
		return workspace.createSession(user.id(), body.title(), body.description());
	}

	@GetMapping("/api/v1/research-sessions/{id}")
	SessionView session(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		return workspace.session(user.id(), id);
	}

	@PatchMapping("/api/v1/research-sessions/{id}")
	SessionView updateSession(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
			@Valid @RequestBody SessionRequest body) {
		return workspace.updateSession(user.id(), id, body.title(), body.description());
	}

	/** Deletes the session; its findings become unfiled. */
	@DeleteMapping("/api/v1/research-sessions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteSession(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		workspace.deleteSession(user.id(), id);
	}

	@GetMapping(value = "/api/v1/research-sessions/{id}/export", produces = "text/markdown")
	ResponseEntity<String> export(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("research-session-" + id + ".md").build().toString())
			.body(workspace.exportMarkdown(user.id(), id));
	}

	// --- Findings ----------------------------------------------------------------

	@PostMapping("/api/v1/findings")
	@ResponseStatus(HttpStatus.CREATED)
	FindingView save(@AuthenticationPrincipal UserPrincipal user, @Valid @RequestBody SaveFindingRequest body) {
		return workspace.saveFinding(user.id(), body.sessionId(), body.source().toReference(), body.note(), body.tags());
	}

	/**
	 * @param unfiled only findings not in a session
	 * @param q matches quote or note text
	 */
	@GetMapping("/api/v1/findings")
	List<FindingView> findings(@AuthenticationPrincipal UserPrincipal user,
			@RequestParam(required = false) @Positive Long sessionId,
			@RequestParam(defaultValue = "false") boolean unfiled,
			@RequestParam(required = false) @Size(max = 200) String q,
			@RequestParam(required = false) @Size(max = 40) String tag) {
		return workspace.findings(user.id(), sessionId, unfiled, q, tag);
	}

	@PatchMapping("/api/v1/findings/{id}")
	FindingView update(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
			@Valid @RequestBody UpdateFindingRequest body) {
		return workspace.updateFinding(user.id(), id, body.sessionId(), body.note(), body.tags());
	}

	@DeleteMapping("/api/v1/findings/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
		workspace.deleteFinding(user.id(), id);
	}

}
