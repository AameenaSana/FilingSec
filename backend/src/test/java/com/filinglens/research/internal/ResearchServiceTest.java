package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.filinglens.ai.AiModel;
import com.filinglens.ai.AiModel.AiResponse;
import com.filinglens.ai.AiModel.Cite;
import com.filinglens.ai.AiModel.Segment;
import com.filinglens.ai.AiNotConfiguredException;
import com.filinglens.ai.AiUsage;
import com.filinglens.ai.QuotaExceededException;
import com.filinglens.filing.FilingCatalog;
import com.filinglens.filing.FilingRef;
import com.filinglens.platform.error.ConflictException;
import com.filinglens.platform.error.InvalidRequestException;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.research.internal.ConversationRepository.ConversationRow;
import com.filinglens.research.internal.IndexRepository.ChunkRow;
import com.filinglens.research.internal.IndexingService.Readiness;
import com.filinglens.research.internal.ResearchViews.FilingInfo;
import com.filinglens.research.internal.ResearchViews.MessageView;
import com.filinglens.research.internal.Retriever.Retrieved;

class ResearchServiceTest {

	static final UUID USER = UUID.randomUUID();

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	static final FilingInfo APPLE = new FilingInfo("0000320193-25-000079", "0000320193", "Apple Inc.", "10-K",
			LocalDate.of(2025, 9, 27), LocalDate.of(2025, 10, 31));

	static final FilingInfo MICROSOFT = new FilingInfo("0000950170-25-100235", "0000789019", "Microsoft Corp", "10-K",
			LocalDate.of(2025, 6, 30), LocalDate.of(2025, 7, 30));

	final ConversationRepository conversations = mock(ConversationRepository.class);

	final FilingCatalog filings = mock(FilingCatalog.class);

	final IndexingService indexing = mock(IndexingService.class);

	final Retriever retriever = mock(Retriever.class);

	final AiModel model = mock(AiModel.class);

	final AiUsage usage = mock(AiUsage.class);

	final ResearchProperties properties = new ResearchProperties(
			new ResearchProperties.Embeddings(null, URI.create("https://api.voyageai.com"), "voyage-4", 1024, 64), 12);

	// Runs the streaming task inline so its effects can be verified.
	final ResearchService service = new ResearchService(conversations, filings, indexing, retriever, model, usage,
			Runnable::run, properties, Clock.fixed(NOW, ZoneOffset.UTC));

	@BeforeEach
	void setUp() {
		when(conversations.find(USER, 1)).thenReturn(Optional.of(new ConversationRow(1, "Apple 10-K", NOW)));
		when(conversations.filingIds(1)).thenReturn(List.of(11L));
		when(model.available()).thenReturn(true);
		when(indexing.prepare(anyLong())).thenReturn(Readiness.READY);
		when(conversations.addMessage(anyLong(), eq("USER"), anyString(), anyString(), any())).thenReturn(100L);
		when(conversations.addMessage(anyLong(), eq("ASSISTANT"), anyString(), anyString(), any())).thenReturn(101L);
		when(conversations.messages(anyLong()))
			.thenReturn(List.of(new MessageView(101, "ASSISTANT", "", "COMPLETE", NOW, List.of())));
	}

	@Test
	void answersFromRetrievedPassagesAndStoresVerifiedCitations() {
		ChunkRow chunk = chunk(5, 11, "Generative AI features may not be successful.");
		when(retriever.retrieve(eq(List.of(11L)), anyString(), eq(12))).thenReturn(List.of(new Retrieved(chunk, 0.03, 1, 2)));
		when(conversations.filingInfo(List.of(11L))).thenReturn(Map.of(11L, APPLE));
		AiResponse response = new AiResponse(List.of(new Segment("AI features may fail.",
				List.of(new Cite(0, 0, 1, "Generative AI features may not be successful.")))), false, "end_turn",
				"claude-opus-5-5", 900, 30, 0);
		when(model.generate(argThat(r -> r.passages().size() == 1
				&& r.passages().getFirst().title().equals("Apple Inc. 10-K (period ended 2025-09-27) — Risk Factors")
				&& r.prompt().equals("What AI risks?") && !r.system().contains("several companies")), any()))
			.thenReturn(response);

		service.ask(USER, 1, "  What AI risks?  ");

		verify(conversations).saveTrace(eq(101L), anyList());
		verify(conversations).finishMessage(101, "AI features may fail.", "COMPLETE", "claude-opus-5-5", 900L, 30L, 0L,
				null);
		verify(conversations).saveCitations(eq(101L), argThat(c -> c.size() == 1 && c.getFirst().verified()
				&& c.getFirst().blockRefs().equals(List.of("10K.ITEM_1A/p2"))));
		verify(usage).record(USER, "answer", response);
	}

	@Test
	void crossCompanyConversationsRetrieveEvenlyPerCompany() {
		when(conversations.filingIds(1)).thenReturn(List.of(11L, 22L));
		when(conversations.filingInfo(List.of(11L, 22L))).thenReturn(Map.of(11L, APPLE, 22L, MICROSOFT));
		ChunkRow a1 = chunk(1, 11, "Apple AI 1");
		ChunkRow a2 = chunk(2, 11, "Apple AI 2");
		ChunkRow m1 = chunk(3, 22, "Microsoft AI 1");
		when(retriever.retrieve(eq(List.of(11L)), anyString(), eq(6)))
			.thenReturn(List.of(new Retrieved(a1, 1, 1, 1), new Retrieved(a2, 0.5, 2, 2)));
		when(retriever.retrieve(eq(List.of(22L)), anyString(), eq(6))).thenReturn(List.of(new Retrieved(m1, 1, 1, 1)));
		when(model.generate(any(), any()))
			.thenReturn(new AiResponse(List.of(new Segment("ok", List.of())), false, "end_turn", "m", 1, 1, 0));

		service.ask(USER, 1, "Compare AI risks");

		verify(model).generate(argThat(r -> r.system().contains("several companies")
				&& r.passages().stream().map(p -> p.blocks().getFirst()).toList()
					.equals(List.of("Apple AI 1", "Microsoft AI 1", "Apple AI 2"))), any());
	}

	@Test
	void refusalsAreStoredWithoutCitations() {
		when(retriever.retrieve(anyList(), anyString(), anyInt())).thenReturn(List.of());
		when(conversations.filingInfo(anyList())).thenReturn(Map.of(11L, APPLE));
		when(model.generate(any(), any())).thenReturn(new AiResponse(List.of(), true, "refusal", "claude-opus-5-5", 50, 0, 0));

		service.ask(USER, 1, "question");

		verify(conversations).finishMessage(eq(101L), anyString(), eq("REFUSED"), any(), any(), any(), any(), any());
		verify(conversations, never()).saveCitations(anyLong(), anyList());
	}

	@Test
	void failuresMarkTheAnswerFailed() {
		when(conversations.filingInfo(anyList())).thenReturn(Map.of(11L, APPLE));
		when(retriever.retrieve(anyList(), anyString(), anyInt())).thenThrow(new IllegalStateException("db down"));

		service.ask(USER, 1, "question");

		verify(conversations).finishMessage(101, "", "FAILED", null, null, null, null, "db down");
	}

	@Test
	void refusesBeforeStreamingWhenNotPossible() {
		assertThatThrownBy(() -> service.ask(USER, 2, "q")).isInstanceOf(NotFoundException.class);

		when(indexing.prepare(11)).thenReturn(Readiness.PREPARING);
		assertThatThrownBy(() -> service.ask(USER, 1, "q")).isInstanceOf(ConflictException.class)
			.extracting("code")
			.isEqualTo("not-ready");

		when(indexing.prepare(11)).thenReturn(Readiness.READY);
		doThrow(new QuotaExceededException()).when(usage).checkQuota(USER);
		assertThatThrownBy(() -> service.ask(USER, 1, "q")).isInstanceOf(QuotaExceededException.class);

		when(model.available()).thenReturn(false);
		assertThatThrownBy(() -> service.ask(USER, 1, "q")).isInstanceOf(AiNotConfiguredException.class);
		verify(conversations, never()).addMessage(anyLong(), anyString(), anyString(), anyString(), any());
	}

	@Test
	void crossCompanyComparisonResolvesEachCompanysLatestFiling() {
		FilingRef apple = new FilingRef(11, 1, APPLE.accessionNumber(), APPLE.cik(), "Apple Inc.", "10-K",
				APPLE.filingDate(), APPLE.reportDate(), "a.htm", null, "DISCOVERED");
		FilingRef microsoft = new FilingRef(22, 2, MICROSOFT.accessionNumber(), MICROSOFT.cik(), "Microsoft Corp", "10-K",
				MICROSOFT.filingDate(), MICROSOFT.reportDate(), "m.htm", null, "DISCOVERED");
		when(filings.latestFiling("320193", "10-K", 2025)).thenReturn(Optional.of(apple));
		when(filings.latestFiling("789019", "10-K", 2025)).thenReturn(Optional.of(microsoft));
		when(conversations.create(eq(USER), anyString(), eq(List.of(11L, 22L)), any())).thenReturn(1L);
		when(conversations.filings(1)).thenReturn(List.of(APPLE, MICROSOFT));
		when(conversations.filingIds(1)).thenReturn(List.of(11L, 22L));

		var view = service.createCrossCompany(USER, List.of("320193", "789019"), "10-K", 2025, "AI risks");

		verify(conversations).create(USER, "AI risks: Apple Inc. vs Microsoft Corp", List.of(11L, 22L), NOW);
		assertThat(view.filings()).hasSize(2);

		when(filings.latestFiling("1", "10-K", null)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.createCrossCompany(USER, List.of("320193", "1"), "10-K", null, null))
			.isInstanceOf(InvalidRequestException.class)
			.extracting("code")
			.isEqualTo("no-filing");
	}

	static ChunkRow chunk(long id, long filingId, String text) {
		return new ChunkRow(id, filingId, "10K.ITEM_1A", "Risk Factors", List.of(41), List.of("10K.ITEM_1A/p2"),
				List.of(text));
	}

}
