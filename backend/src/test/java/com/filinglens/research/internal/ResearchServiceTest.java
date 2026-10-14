package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

import com.filinglens.filing.FilingCatalog;
import com.filinglens.platform.error.ConflictException;
import com.filinglens.platform.error.NotFoundException;
import com.filinglens.research.internal.AnswerModel.Answer;
import com.filinglens.research.internal.AnswerModel.Cite;
import com.filinglens.research.internal.AnswerModel.Segment;
import com.filinglens.research.internal.ConversationRepository.ConversationRow;
import com.filinglens.research.internal.IndexRepository.ChunkRow;
import com.filinglens.research.internal.IndexingService.Readiness;
import com.filinglens.research.internal.ResearchViews.FilingInfo;
import com.filinglens.research.internal.ResearchViews.MessageView;
import com.filinglens.research.internal.Retriever.Retrieved;

class ResearchServiceTest {

	static final UUID USER = UUID.randomUUID();

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	final ConversationRepository conversations = mock(ConversationRepository.class);

	final IndexingService indexing = mock(IndexingService.class);

	final Retriever retriever = mock(Retriever.class);

	final AnswerModel model = mock(AnswerModel.class);

	final ResearchProperties properties = new ResearchProperties(
			new ResearchProperties.Llm("key", "claude-opus-5-5", 64000, "medium"),
			new ResearchProperties.Embeddings(null, URI.create("https://api.voyageai.com"), "voyage-4", 1024, 64),
			1_000_000, 12);

	// Runs the streaming task inline so its effects can be verified.
	final ResearchService service = new ResearchService(conversations, mock(FilingCatalog.class), indexing, retriever,
			model, Runnable::run, properties, Clock.fixed(NOW, ZoneOffset.UTC));

	@BeforeEach
	void setUp() {
		when(conversations.find(USER, 1)).thenReturn(Optional.of(new ConversationRow(1, "Apple 10-K", NOW)));
		when(conversations.filingIds(1)).thenReturn(List.of(11L));
		when(model.available()).thenReturn(true);
		when(indexing.prepare(11)).thenReturn(Readiness.READY);
		when(conversations.addMessage(eq(1L), eq("USER"), anyString(), anyString(), any())).thenReturn(100L);
		when(conversations.addMessage(eq(1L), eq("ASSISTANT"), anyString(), anyString(), any())).thenReturn(101L);
	}

	@Test
	void answersFromRetrievedPassagesAndStoresVerifiedCitations() {
		ChunkRow chunk = new ChunkRow(5, 11, "10K.ITEM_1A", "Risk Factors", List.of(41), List.of("10K.ITEM_1A/p2"),
				List.of("Generative AI features may not be successful."));
		when(retriever.retrieve(eq(List.of(11L)), anyString(), eq(12))).thenReturn(List.of(new Retrieved(chunk, 0.03, 1, 2)));
		when(conversations.filingInfo(List.of(11L))).thenReturn(Map.of(11L, new FilingInfo("0000320193-25-000079",
				"0000320193", "Apple Inc.", "10-K", LocalDate.of(2025, 9, 27), LocalDate.of(2025, 10, 31))));
		when(model.answer(argThat(r -> r.passages().size() == 1
				&& r.passages().getFirst().title().equals("Apple Inc. 10-K (period ended 2025-09-27) — Risk Factors")
				&& r.question().equals("What AI risks?")), any()))
			.thenReturn(new Answer(List.of(new Segment("AI features may fail.",
					List.of(new Cite(0, 0, 1, "Generative AI features may not be successful.")))), false, "end_turn",
					"claude-opus-5-5", 900, 30, 0));
		when(conversations.messages(1)).thenReturn(List.of(new MessageView(101, "ASSISTANT", "AI features may fail.",
				"COMPLETE", NOW, List.of())));

		service.ask(USER, 1, "  What AI risks?  ");

		verify(conversations).saveTrace(eq(101L), anyList());
		verify(conversations).finishMessage(101, "AI features may fail.", "COMPLETE", "claude-opus-5-5", 900L, 30L, 0L,
				null);
		verify(conversations).saveCitations(eq(101L), argThat(c -> c.size() == 1 && c.getFirst().verified()
				&& c.getFirst().blockRefs().equals(List.of("10K.ITEM_1A/p2"))));
		verify(conversations).recordUsage(USER, "answer", "claude-opus-5-5", 900, 30, NOW);
	}

	@Test
	void refusalsAreStoredWithoutCitations() {
		when(retriever.retrieve(anyList(), anyString(), anyInt())).thenReturn(List.of());
		when(conversations.filingInfo(anyList())).thenReturn(Map.of());
		when(model.answer(any(), any()))
			.thenReturn(new Answer(List.of(), true, "refusal", "claude-opus-5-5", 50, 0, 0));
		when(conversations.messages(1)).thenReturn(List.of(new MessageView(101, "ASSISTANT", "", "REFUSED", NOW, List.of())));

		service.ask(USER, 1, "question");

		verify(conversations).finishMessage(eq(101L), anyString(), eq("REFUSED"), any(), any(), any(), any(), any());
		verify(conversations, never()).saveCitations(anyLong(), anyList());
	}

	@Test
	void modelFailuresMarkTheAnswerFailed() {
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
		when(conversations.tokensUsedSince(eq(USER), any())).thenReturn(1_000_000L);
		assertThatThrownBy(() -> service.ask(USER, 1, "q")).extracting("code").isEqualTo("quota-exceeded");

		when(model.available()).thenReturn(false);
		assertThatThrownBy(() -> service.ask(USER, 1, "q")).extracting("code").isEqualTo("ai-not-configured");
		verify(conversations, never()).addMessage(anyLong(), anyString(), anyString(), anyString(), any());
	}

}
