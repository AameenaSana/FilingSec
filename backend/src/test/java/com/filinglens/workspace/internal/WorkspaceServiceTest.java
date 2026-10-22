package com.filinglens.workspace.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.filinglens.comparison.ChangeLookup;
import com.filinglens.comparison.ChangeSnapshot;
import com.filinglens.document.BlockText;
import com.filinglens.document.DocumentContent;
import com.filinglens.document.LocatedBlock;
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
import com.filinglens.workspace.internal.WorkspaceService.SourceReference;
import com.filinglens.workspace.internal.WorkspaceViews.FindingView;

class WorkspaceServiceTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final UUID USER = UUID.randomUUID();

	static final String ACCESSION = "0000320193-25-000079";

	WorkspaceRepository repository = mock(WorkspaceRepository.class);

	CitationLookup citations = mock(CitationLookup.class);

	ChangeLookup changes = mock(ChangeLookup.class);

	FilingCatalog filings = mock(FilingCatalog.class);

	DocumentContent documents = mock(DocumentContent.class);

	WorkspaceService service;

	@BeforeEach
	void setUp() {
		var properties = new FilingLensProperties(new FilingLensProperties.App(URI.create("https://lens.example/")),
				null, null, null, null);
		service = new WorkspaceService(repository, citations, changes, filings, documents, properties,
				Clock.fixed(NOW, ZoneOffset.UTC));
		when(repository.insertFinding(any(), any(), any(), any(), any(), any())).thenReturn(7L);
		when(repository.finding(USER, 7L)).thenReturn(Optional.of(finding(null, null)));
	}

	@Test
	void citationQuoteComesFromTheStoredCitationNotTheClient() {
		when(citations.find(USER, 3L)).thenReturn(Optional.of(new CitationSnapshot(3, 11, 5, ACCESSION, "Apple Inc.",
				"10-K", LocalDate.of(2025, 9, 27), "10K.ITEM_1A", "Item 1A. Risk Factors",
				List.of("10K.ITEM_1A/p4", "10K.ITEM_1A/p5"), 42, "AI features may not be successful.")));

		service.saveFinding(USER, null, new SourceReference("CITATION", 3L, null, null, null, null, null), " why ",
				List.of("AI Risk", "ai  risk", " "));

		var source = ArgumentCaptor.forClass(FindingSource.class);
		verify(repository).insertFinding(eq(USER), isNull(), source.capture(), eq("why"), eq(List.of("ai-risk")),
				eq(NOW));
		assertThat(source.getValue().quotedText()).isEqualTo("AI features may not be successful.");
		assertThat(source.getValue().blockOrdinal()).isEqualTo(42);
		assertThat(source.getValue().conversationId()).isEqualTo(11L);
		verify(repository, never()).touchSession(anyLong(), any());
	}

	@Test
	void citationOfAnotherUserIsNotFound() {
		when(citations.find(USER, 3L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.saveFinding(USER, null,
				new SourceReference("CITATION", 3L, null, null, null, null, null), null, null))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void modifiedChangeKeepsBothVersions() {
		var base = new ChangeSnapshot.Side(1, "0000320193-24-000123", LocalDate.of(2024, 9, 28), "10K.ITEM_1A/p3", 30,
				"Old wording.");
		var target = new ChangeSnapshot.Side(2, ACCESSION, LocalDate.of(2025, 9, 27), "10K.ITEM_1A/p4", 31,
				"New wording.");
		when(changes.find(9, "10K.ITEM_1A", 2)).thenReturn(Optional.of(new ChangeSnapshot(9, 2, "MODIFIED",
				"10K.ITEM_1A", "Item 1A. Risk Factors", "Apple Inc.", "10-K", base, target)));

		service.saveFinding(USER, null, new SourceReference("CHANGE", null, 9L, "10K.ITEM_1A", 2, null, null), null,
				null);

		var source = ArgumentCaptor.forClass(FindingSource.class);
		verify(repository).insertFinding(eq(USER), isNull(), source.capture(), isNull(), eq(List.of()), eq(NOW));
		assertThat(source.getValue()).extracting(FindingSource::filingId, FindingSource::quotedText,
				FindingSource::previousText, FindingSource::previousBlockOrdinal, FindingSource::changeType)
			.containsExactly(2L, "New wording.", "Old wording.", 30, "MODIFIED");
	}

	@Test
	void removedChangeQuotesTheEarlierFiling() {
		var base = new ChangeSnapshot.Side(1, "0000320193-24-000123", LocalDate.of(2024, 9, 28), "10K.ITEM_1A/p3", 30,
				"Dropped risk.");
		when(changes.find(9, "10K.ITEM_1A", 1)).thenReturn(Optional.of(new ChangeSnapshot(9, 1, "REMOVED",
				"10K.ITEM_1A", "Item 1A. Risk Factors", "Apple Inc.", "10-K", base, null)));

		service.saveFinding(USER, null, new SourceReference("CHANGE", null, 9L, "10K.ITEM_1A", 1, null, null), null,
				null);

		var source = ArgumentCaptor.forClass(FindingSource.class);
		verify(repository).insertFinding(any(), any(), source.capture(), any(), any(), any());
		assertThat(source.getValue().filingId()).isEqualTo(1L);
		assertThat(source.getValue().quotedText()).isEqualTo("Dropped risk.");
		assertThat(source.getValue().previousText()).isNull();
	}

	@Test
	void passageIsReadFromTheParsedDocumentAndFiledInASession() {
		when(repository.session(USER, 4L)).thenReturn(Optional.of(new SessionRow(4, "AI", null, NOW, NOW)));
		when(filings.findByAccession(ACCESSION)).thenReturn(Optional.of(new FilingRef(5, 1, ACCESSION, "0000320193",
				"Apple Inc.", "10-K", LocalDate.of(2025, 10, 31), LocalDate.of(2025, 9, 27), "aapl.htm", null,
				"FETCHED")));
		when(documents.block(5, 12)).thenReturn(Optional.of(new LocatedBlock(
				new BlockText(12, "10K.ITEM_7/p2", "PARAGRAPH", "Net sales grew.", "h"), "10K.ITEM_7", "Item 7. MD&A")));

		service.saveFinding(USER, 4L, new SourceReference("PASSAGE", null, null, null, null, ACCESSION, 12), null, null);

		var source = ArgumentCaptor.forClass(FindingSource.class);
		verify(repository).insertFinding(eq(USER), eq(4L), source.capture(), any(), any(), any());
		assertThat(source.getValue().blockRefs()).containsExactly("10K.ITEM_7/p2");
		assertThat(source.getValue().sectionTitle()).isEqualTo("Item 7. MD&A");
		verify(repository).touchSession(4L, NOW);
	}

	@Test
	void rejectsSessionsOfOtherUsersAndIncompleteReferences() {
		when(repository.session(USER, 4L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.saveFinding(USER, 4L,
				new SourceReference("PASSAGE", null, null, null, null, ACCESSION, 12), null, null))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> service.saveFinding(USER, null,
				new SourceReference("CHANGE", null, 9L, null, 1, null, null), null, null))
			.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.updateFinding(USER, 7, 4L, null, null)).isInstanceOf(NotFoundException.class);
		verify(repository, never()).insertFinding(any(), any(), any(), any(), any(), any());
	}

	@Test
	void partialUpdateKeepsFieldsThatWereNotSent() {
		when(repository.session(USER, 4L)).thenReturn(Optional.of(new SessionRow(4, "AI risk", null, NOW, NOW)));
		when(repository.finding(USER, 7L)).thenReturn(Optional.of(finding("Keep me", null)));
		when(repository.updateFinding(any(), anyLong(), any(), any(), any(), any())).thenReturn(true);

		service.updateFinding(USER, 7, new WorkspaceService.FindingChanges(true, 4L, false, null, false, null));

		verify(repository).updateFinding(USER, 7, 4L, "Keep me", List.of("ai"), NOW);
	}

	@Test
	void partialUpdateClearsFieldsSentAsNull() {
		when(repository.finding(USER, 7L)).thenReturn(Optional.of(finding("Old note", null)));
		when(repository.updateFinding(any(), anyLong(), any(), any(), any(), any())).thenReturn(true);

		service.updateFinding(USER, 7, new WorkspaceService.FindingChanges(false, null, true, null, true, List.of()));

		verify(repository).updateFinding(USER, 7, null, null, List.of(), NOW);
	}

	@Test
	void savingTheSameSourceTwiceInOneSessionIsRejected() {
		when(filings.findByAccession(ACCESSION)).thenReturn(Optional.of(new FilingRef(11, 1, ACCESSION, "0000320193",
				"Apple Inc.", "10-K", null, null, null, null, "FETCHED")));
		when(documents.block(11, 12)).thenReturn(Optional.of(new LocatedBlock(
				new BlockText(12, "10K.ITEM_1A/p12", "PARAGRAPH", "AI may fail.", "h"), "10K.ITEM_1A", "Risk Factors")));
		when(repository.hasDuplicate(eq(USER), isNull(), any())).thenReturn(true);

		assertThatThrownBy(() -> service.saveFinding(USER, null,
				new SourceReference("PASSAGE", null, null, null, null, ACCESSION, 12), null, null))
			.isInstanceOf(ConflictException.class);
		verify(repository, never()).insertFinding(any(), any(), any(), any(), any(), any());
	}

	@Test
	void exportEscapesUserTextSoItCannotAddLinksOrHeadings() {
		assertThat(WorkspaceService.escapeMarkdown("Line1\n# Heading\n[x](javascript:alert(1)) <b>*bold*</b>"))
			.isEqualTo("Line1  \n\\# Heading  \n\\[x\\](javascript:alert(1)) \\<b\\>\\*bold\\*\\</b\\>");
	}

	@Test
	void limitsTags() {
		assertThat(WorkspaceService.normalizeTags(List.of("Revenue", "revenue", "x".repeat(60))))
			.containsExactly("revenue", "x".repeat(40));
		assertThatThrownBy(() -> WorkspaceService
			.normalizeTags(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")))
			.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void exportsMarkdownOldestFirstWithSourceLinks() {
		when(repository.session(USER, 4L)).thenReturn(Optional.of(new SessionRow(4, "AI risk", "Tracking AI.", NOW, NOW)));
		when(repository.findings(USER, 4L, false, null, null)).thenReturn(List.of(
				finding("Second note", "MODIFIED"), finding("First note", null)));

		String md = service.exportMarkdown(USER, 4L);

		assertThat(md).startsWith("# AI risk\n\nTracking AI.\n");
		assertThat(md.indexOf("First note")).isLessThan(md.indexOf("Second note"));
		assertThat(md).contains("## 1. Apple Inc. 10-K (period ended 2025-09-27) — Item 1A. Risk Factors")
			.contains("**Change:** Modified")
			.contains("Earlier text:\n\n> Old wording.\n")
			.contains("> AI features\n> may not be successful.\n")
			.contains("**Tags:** ai")
			.contains("https://www.sec.gov/Archives/edgar/data/320193/000032019325000079/")
			.contains("(https://lens.example/filings/" + ACCESSION + "?block=42)");
	}

	static FindingView finding(String note, String changeType) {
		return new FindingView(7, null, changeType == null ? "CITATION" : "CHANGE",
				new WorkspaceViews.FilingRef(ACCESSION, "0000320193", "Apple Inc.", "10-K", LocalDate.of(2025, 9, 27)),
				"10K.ITEM_1A", "Item 1A. Risk Factors", List.of("10K.ITEM_1A/p4"), 42,
				"AI features\nmay not be successful.", changeType == null ? null : "Old wording.", null, null,
				changeType, null, null, null, note, List.of("ai"), NOW, NOW);
	}

}
