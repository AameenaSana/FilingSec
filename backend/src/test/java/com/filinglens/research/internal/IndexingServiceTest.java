package com.filinglens.research.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.filinglens.document.DocumentContent;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobQueue;
import com.filinglens.research.internal.IndexRepository.IndexState;
import com.filinglens.research.internal.IndexRepository.UnembeddedChunk;
import com.filinglens.research.internal.IndexingService.Readiness;

import tools.jackson.databind.json.JsonMapper;

class IndexingServiceTest {

	static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	final IndexRepository index = mock(IndexRepository.class);

	final DocumentContent documents = mock(DocumentContent.class);

	final EmbeddingModel embeddings = mock(EmbeddingModel.class);

	final JobQueue jobs = mock(JobQueue.class);

	final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	final IndexingService service = new IndexingService(index, documents, embeddings, jobs, clock);

	final Job job = new Job(1, IndexingService.IndexJob.TYPE, "{\"filingId\": 7}", 1, 5);

	@Test
	void indexesForKeywordSearchWhenEmbeddingFailsAndEmbedsLater() {
		when(documents.read(7)).thenReturn(List.of(ChunkerAndCitationTest.section("10K.ITEM_1A", "AI may fail.")));
		when(embeddings.available()).thenReturn(true);
		when(embeddings.modelName()).thenReturn("voyage-4");
		when(embeddings.embedDocuments(any())).thenThrow(new IllegalStateException("Embedding request failed with status 429"));

		new IndexingService.IndexJob(index, documents, embeddings, jobs, JsonMapper.builder().build(), clock).handle(job);

		verify(index).replaceChunks(eq(7L), any(), isNull(), eq("voyage-4"), startsWith("Semantic search unavailable"),
				eq(NOW));
		verify(jobs).enqueueUnique(IndexingService.EmbedJob.TYPE, Map.of("filingId", 7L),
				IndexingService.EmbedJob.TYPE + ":7", IndexingService.FIRST_EMBEDDING_DELAY);
		verify(index, never()).markFailed(anyLong(), anyString(), any());
	}

	@Test
	void embedJobAddsVectorsToKeywordOnlyChunks() {
		float[] vector = new float[] { 1 };
		when(embeddings.available()).thenReturn(true);
		when(embeddings.modelName()).thenReturn("voyage-4");
		when(index.unembeddedChunks(7)).thenReturn(List.of(new UnembeddedChunk(40, "Risk Factors", "AI may fail.")));
		when(embeddings.embedDocuments(List.of("Risk Factors\n\nAI may fail."))).thenReturn(List.of(vector));

		new IndexingService.EmbedJob(index, embeddings, JsonMapper.builder().build(), clock).handle(job);

		verify(index).storeEmbeddings(7, List.of(40L), List.of(vector), "voyage-4", NOW);
	}

	@Test
	void recentlyFailedIndexIsReportedUnavailableInsteadOfRetriedOnEveryPoll() {
		when(index.state(7)).thenReturn(Optional.of(state("FAILED", false, NOW.minus(Duration.ofMinutes(1)))));

		assertThat(service.prepare(7)).isEqualTo(Readiness.UNAVAILABLE);
		verify(index, never()).startIndex(anyLong(), anyInt(), any());
	}

	@Test
	void failedIndexIsRetriedAfterAWhile() {
		when(index.state(7)).thenReturn(Optional.of(state("FAILED", false, NOW.minus(Duration.ofHours(1)))));
		when(documents.state(7)).thenReturn(DocumentContent.State.PARSED);
		when(index.startIndex(7, 1, NOW)).thenReturn(true);

		assertThat(service.prepare(7)).isEqualTo(Readiness.PREPARING);
		verify(jobs).enqueueUnique(IndexingService.IndexJob.TYPE, Map.of("filingId", 7L), IndexingService.IndexJob.TYPE + ":7");
	}

	@Test
	void keywordOnlyFilingIsReadyAndEmbeddingIsRetriedAfterAWhile() {
		when(embeddings.available()).thenReturn(true);
		when(index.state(7)).thenReturn(Optional.of(state("INDEXED", false, NOW.minus(Duration.ofMinutes(5)))));
		assertThat(service.prepare(7)).isEqualTo(Readiness.READY);
		verify(jobs, never()).enqueueUnique(anyString(), any(), anyString(), any());

		when(index.state(7)).thenReturn(Optional.of(state("INDEXED", false, NOW.minus(Duration.ofHours(1)))));
		assertThat(service.prepare(7)).isEqualTo(Readiness.READY);
		verify(jobs).enqueueUnique(IndexingService.EmbedJob.TYPE, Map.of("filingId", 7L),
				IndexingService.EmbedJob.TYPE + ":7", Duration.ZERO);
	}

	private static IndexState state(String status, boolean embedded, Instant updatedAt) {
		return new IndexState(status, embedded, null, 1, null, updatedAt);
	}

}
