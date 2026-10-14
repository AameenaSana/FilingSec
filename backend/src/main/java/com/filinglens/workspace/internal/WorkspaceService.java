package com.filinglens.workspace.internal;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.filinglens.comparison.ChangeLookup;
import com.filinglens.comparison.ChangeSnapshot;
import com.filinglens.document.DocumentContent;
import com.filinglens.document.LocatedBlock;
import com.filinglens.edgar.EdgarUrls;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.config.FilingLensProperties;
import com.filinglens.platform.error.ConflictException;
import com.filinglens.platform.error.InvalidRequestException;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.research.CitationLookup;
import com.filinglens.research.CitationSnapshot;
import com.filinglens.workspace.internal.WorkspaceRepository.FindingSource;
import com.filinglens.workspace.internal.WorkspaceRepository.SessionRow;
import com.filinglens.workspace.internal.WorkspaceViews.FindingView;
import com.filinglens.workspace.internal.WorkspaceViews.SessionSummary;
import com.filinglens.workspace.internal.WorkspaceViews.SessionView;

/**
 * Research sessions and findings. A finding's quote and source are resolved on
 * the server from a citation, change or passage reference, so saved evidence is
 * always genuine filing text with a working link to its location.
 */
@Service
class WorkspaceService {

	static final int MAX_FINDINGS_PER_USER = 5000;

	static final int MAX_SESSIONS_PER_USER = 200;

	static final int MAX_TAGS = 10;

	private final WorkspaceRepository workspace;

	private final CitationLookup citations;

	private final ChangeLookup changes;

	private final FilingCatalog filings;

	private final DocumentContent documents;

	private final String publicUrl;

	private final Clock clock;

	WorkspaceService(WorkspaceRepository workspace, CitationLookup citations, ChangeLookup changes,
			FilingCatalog filings, DocumentContent documents, FilingLensProperties properties, Clock clock) {
		this.workspace = workspace;
		this.citations = citations;
		this.changes = changes;
		this.filings = filings;
		this.documents = documents;
		this.publicUrl = properties.app().publicUrl().toString().replaceAll("/+$", "");
		this.clock = clock;
	}

	/**
	 * What to save. Exactly one kind of reference is used, depending on {@code type}:
	 * CITATION ({@code citationId}), CHANGE ({@code comparisonId}, {@code sectionKey},
	 * {@code changeOrdinal}) or PASSAGE ({@code accessionNumber}, {@code blockOrdinal}).
	 */
	record SourceReference(String type, Long citationId, Long comparisonId, String sectionKey, Integer changeOrdinal,
			String accessionNumber, Integer blockOrdinal) {
	}

	// --- Sessions ----------------------------------------------------------------

	List<SessionSummary> sessions(UUID userId) {
		return workspace.sessions(userId);
	}

	SessionView createSession(UUID userId, String title, String description) {
		if (workspace.sessions(userId).size() >= MAX_SESSIONS_PER_USER) {
			throw new ConflictException("too-many-sessions",
					"You can keep at most %d research sessions".formatted(MAX_SESSIONS_PER_USER));
		}
		long id = workspace.createSession(userId, title.strip(), blankToNull(description), clock.instant());
		return session(userId, id);
	}

	SessionView session(UUID userId, long id) {
		SessionRow row = sessionRow(userId, id);
		return new SessionView(row.id(), row.title(), row.description(),
				workspace.findings(userId, id, false, null, null), row.createdAt(), row.updatedAt());
	}

	SessionView updateSession(UUID userId, long id, String title, String description) {
		if (!workspace.updateSession(userId, id, title.strip(), blankToNull(description), clock.instant())) {
			throw new NotFoundException("Session", id);
		}
		return session(userId, id);
	}

	/** Deletes the session; its findings are kept as unfiled. */
	void deleteSession(UUID userId, long id) {
		if (!workspace.deleteSession(userId, id)) {
			throw new NotFoundException("Session", id);
		}
	}

	// --- Findings ----------------------------------------------------------------

	FindingView saveFinding(UUID userId, Long sessionId, SourceReference reference, String note, List<String> tags) {
		if (sessionId != null) {
			sessionRow(userId, sessionId);
		}
		if (workspace.countFindings(userId) >= MAX_FINDINGS_PER_USER) {
			throw new ConflictException("too-many-findings",
					"You can keep at most %d findings".formatted(MAX_FINDINGS_PER_USER));
		}
		FindingSource source = resolve(userId, reference);
		long id = workspace.insertFinding(userId, sessionId, source, blankToNull(note), normalizeTags(tags),
				clock.instant());
		if (sessionId != null) {
			workspace.touchSession(sessionId, clock.instant());
		}
		return workspace.finding(userId, id).orElseThrow();
	}

	List<FindingView> findings(UUID userId, Long sessionId, boolean unfiledOnly, String text, String tag) {
		return workspace.findings(userId, sessionId, unfiledOnly, text, tag);
	}

	FindingView updateFinding(UUID userId, long id, Long sessionId, String note, List<String> tags) {
		if (sessionId != null) {
			sessionRow(userId, sessionId);
		}
		if (!workspace.updateFinding(userId, id, sessionId, blankToNull(note), normalizeTags(tags), clock.instant())) {
			throw new NotFoundException("Finding", id);
		}
		return workspace.finding(userId, id).orElseThrow();
	}

	void deleteFinding(UUID userId, long id) {
		if (!workspace.deleteFinding(userId, id)) {
			throw new NotFoundException("Finding", id);
		}
	}

	/** The session as Markdown: each finding's quote, note, tags and links to the source. */
	String exportMarkdown(UUID userId, long sessionId) {
		SessionView session = session(userId, sessionId);
		StringBuilder md = new StringBuilder("# ").append(session.title()).append("\n\n");
		if (session.description() != null) {
			md.append(session.description()).append("\n\n");
		}
		md.append("_Exported from FilingLens. Quotes are verbatim from SEC filings._\n");
		int n = 1;
		for (FindingView f : session.findings().reversed()) {
			md.append("\n## ").append(n++).append(". ").append(f.filing().companyName()).append(' ')
				.append(f.filing().formType());
			if (f.filing().reportDate() != null) {
				md.append(" (period ended ").append(f.filing().reportDate()).append(')');
			}
			md.append(" — ").append(f.sectionTitle()).append("\n\n");
			if (f.changeType() != null) {
				md.append("**Change:** ").append(f.changeType().charAt(0)).append(f.changeType().substring(1).toLowerCase(Locale.ROOT))
					.append("\n\n");
			}
			if (f.previousText() != null) {
				md.append("Earlier text:\n\n").append(quote(f.previousText())).append("\nLater text:\n\n");
			}
			md.append(quote(f.quotedText())).append('\n');
			if (f.note() != null) {
				md.append("**Note:** ").append(f.note()).append("\n\n");
			}
			if (!f.tags().isEmpty()) {
				md.append("**Tags:** ").append(String.join(", ", f.tags())).append("\n\n");
			}
			md.append("Source: [SEC filing](").append(EdgarUrls.filingIndexUrl(f.filing().cik(), f.filing().accessionNumber()))
				.append(") · [Passage in FilingLens](").append(publicUrl).append("/filings/")
				.append(f.filing().accessionNumber()).append("?block=").append(f.blockOrdinal()).append(") · ")
				.append(String.join(", ", f.blockRefs())).append('\n');
		}
		return md.toString();
	}

	private FindingSource resolve(UUID userId, SourceReference ref) {
		String type = ref.type() == null ? "" : ref.type();
		return switch (type) {
			case "CITATION" -> {
				CitationSnapshot c = citations.find(userId, required(ref.citationId(), "citationId"))
					.orElseThrow(() -> new NotFoundException("Citation", ref.citationId()));
				yield new FindingSource("CITATION", c.filingId(), c.accessionNumber(), c.companyName(), c.formType(),
						c.reportDate(), c.sectionKey(), c.sectionTitle(), c.blockRefs(), c.firstBlockOrdinal(),
						c.citedText(), null, null, null, null, null, null, c.conversationId());
			}
			case "CHANGE" -> {
				ChangeSnapshot c = changes
					.find(required(ref.comparisonId(), "comparisonId"), required(ref.sectionKey(), "sectionKey"),
							required(ref.changeOrdinal(), "changeOrdinal"))
					.orElseThrow(() -> new NotFoundException("Change", ref.changeOrdinal()));
				// The later filing's text is the quote; for a removal, the earlier text is.
				ChangeSnapshot.Side main = c.target() != null ? c.target() : c.base();
				ChangeSnapshot.Side previous = c.target() != null && "MODIFIED".equals(c.changeType()) ? c.base() : null;
				yield new FindingSource("CHANGE", main.filingId(), main.accessionNumber(), c.companyName(), c.formType(),
						main.reportDate(), c.sectionKey(), c.sectionTitle(), List.of(main.blockRef()),
						main.blockOrdinal(), main.text(), previous == null ? null : previous.text(),
						previous == null ? null : previous.accessionNumber(),
						previous == null ? null : previous.blockOrdinal(), c.changeType(), c.comparisonId(),
						c.changeOrdinal(), null);
			}
			case "PASSAGE" -> {
				FilingRef filing = filings.findByAccession(required(ref.accessionNumber(), "accessionNumber"))
					.orElseThrow(() -> new NotFoundException("Filing", ref.accessionNumber()));
				LocatedBlock located = documents.block(filing.id(), required(ref.blockOrdinal(), "blockOrdinal"))
					.orElseThrow(() -> new NotFoundException("Passage", ref.blockOrdinal()));
				yield new FindingSource("PASSAGE", filing.id(), filing.accessionNumber(), filing.companyName(),
						filing.formType(), filing.reportDate(), located.sectionKey(), located.sectionTitle(),
						List.of(located.block().ref()), located.block().ordinal(), located.block().text(), null, null,
						null, null, null, null, null);
			}
			default -> throw new InvalidRequestException("invalid-source", "Unknown source type: " + type);
		};
	}

	private SessionRow sessionRow(UUID userId, long id) {
		return workspace.session(userId, id).orElseThrow(() -> new NotFoundException("Session", id));
	}

	static List<String> normalizeTags(List<String> tags) {
		if (tags == null) {
			return List.of();
		}
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String tag : tags) {
			String t = tag == null ? "" : tag.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
			if (!t.isEmpty()) {
				normalized.add(t.length() <= 40 ? t : t.substring(0, 40));
			}
		}
		if (normalized.size() > MAX_TAGS) {
			throw new InvalidRequestException("too-many-tags", "Use at most %d tags".formatted(MAX_TAGS));
		}
		return List.copyOf(normalized);
	}

	private static String quote(String text) {
		StringBuilder quoted = new StringBuilder();
		for (String line : text.split("\n")) {
			quoted.append("> ").append(line).append('\n');
		}
		return quoted.toString();
	}

	private static <T> T required(T value, String field) {
		if (value == null || (value instanceof String s && s.isBlank())) {
			throw new InvalidRequestException("invalid-source", "Missing " + field);
		}
		return value;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
