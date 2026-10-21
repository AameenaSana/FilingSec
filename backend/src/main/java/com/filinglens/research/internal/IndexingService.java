package com.filinglens.research.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.filinglens.document.DocumentContent;
import com.filinglens.document.DocumentParsed;
import com.filinglens.document.SectionText;
import com.filinglens.research.internal.IndexRepository.IndexState;
import com.filinglens.research.internal.IndexRepository.UnembeddedChunk;
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;
import com.filinglens.platform.jobs.JobQueue;

import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the retrieval index in step with parsed documents: every parsed filing
 * is chunked and (if an embedding provider is configured) embedded. If embedding
 * fails, the filing is indexed for keyword search at once and embedded later, so
 * an unavailable embedding provider never blocks questions.
 */
@Service
class IndexingService {

	enum Readiness {

		PREPARING, READY, UNAVAILABLE

	}

	/** How long a failed index is reported as unavailable before it is retried. */
	static final Duration RETRY_FAILED_INDEX_AFTER = Duration.ofMinutes(10);

	/** How long after a failed embedding attempt a keyword-only filing is embedded again. */
	static final Duration RETRY_EMBEDDING_AFTER = Duration.ofMinutes(30);

	/** Delay before embedding a filing that was just indexed keyword-only, for rate limits to recover. */
	static final Duration FIRST_EMBEDDING_DELAY = Duration.ofMinutes(2);

	private final IndexRepository index;

	private final DocumentContent documents;

	private final EmbeddingModel embeddings;

	private final JobQueue jobs;

	private final Clock clock;

	IndexingService(IndexRepository index, DocumentContent documents, EmbeddingModel embeddings, JobQueue jobs,
			Clock clock) {
		this.index = index;
		this.documents = documents;
		this.embeddings = embeddings;
		this.jobs = jobs;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void on(DocumentParsed event) {
		requestIndex(event.filingId(), event.parserVersion());
	}

	void requestIndex(long filingId, int parserVersion) {
		if (index.startIndex(filingId, parserVersion, clock.instant())) {
			jobs.enqueueUnique(IndexJob.TYPE, Map.of("filingId", filingId), IndexJob.TYPE + ":" + filingId);
		}
	}

	boolean isEmbedded(long filingId) {
		return index.state(filingId).map(IndexState::embedded).orElse(false);
	}

	/** Readiness of a filing for Q&A, starting whatever preparation is still needed. */
	Readiness prepare(long filingId) {
		IndexState state = index.state(filingId).orElse(null);
		Instant now = clock.instant();
		if (state != null && "INDEXED".equals(state.status())) {
			if (!state.embedded() && embeddings.available() && olderThan(state, RETRY_EMBEDDING_AFTER, now)) {
				requestEmbedding(jobs, filingId, Duration.ZERO);
			}
			return Readiness.READY;
		}
		if (state != null && "INDEXING".equals(state.status())) {
			return Readiness.PREPARING;
		}
		if (state != null && "FAILED".equals(state.status()) && !olderThan(state, RETRY_FAILED_INDEX_AFTER, now)) {
			// Report the failure instead of retrying on every poll; it is retried after a while.
			return Readiness.UNAVAILABLE;
		}
		return switch (documents.state(filingId)) {
			case UNAVAILABLE -> Readiness.UNAVAILABLE;
			case PARSED -> {
				// Parsed before this filing was ever indexed, or indexing failed a while ago: (re)index.
				requestIndex(filingId, state == null || state.parserVersion() == null ? 1 : state.parserVersion());
				yield Readiness.PREPARING;
			}
			case PENDING -> {
				documents.prepare(filingId);
				yield Readiness.PREPARING;
			}
		};
	}

	private static boolean olderThan(IndexState state, Duration age, Instant now) {
		return state.updatedAt() == null || state.updatedAt().plus(age).isBefore(now);
	}

	static void requestEmbedding(JobQueue jobs, long filingId, Duration delay) {
		jobs.enqueueUnique(EmbedJob.TYPE, Map.of("filingId", filingId), EmbedJob.TYPE + ":" + filingId, delay);
	}

	private static String embedText(String sectionTitle, String text) {
		// The section title gives each chunk context the text alone may lack.
		return sectionTitle + "\n\n" + text;
	}

	/** Builds a filing's chunks and, if possible, their embeddings. */
	@Component
	static class IndexJob implements JobHandler {

		static final String TYPE = "research.index";

		private static final Logger log = LoggerFactory.getLogger(IndexJob.class);

		private final IndexRepository index;

		private final DocumentContent documents;

		private final EmbeddingModel embeddings;

		private final JobQueue jobs;

		private final JsonMapper json;

		private final Clock clock;

		IndexJob(IndexRepository index, DocumentContent documents, EmbeddingModel embeddings, JobQueue jobs,
				JsonMapper json, Clock clock) {
			this.index = index;
			this.documents = documents;
			this.embeddings = embeddings;
			this.jobs = jobs;
			this.json = json;
			this.clock = clock;
		}

		@Override
		public String type() {
			return TYPE;
		}

		/** Embedding can wait minutes on rate limits; chunks are stored in their own transaction. */
		@Override
		public boolean transactional() {
			return false;
		}

		@Override
		public void handle(Job job) {
			long filingId = filingId(job);
			List<SectionText> sections = documents.read(filingId);
			if (sections.isEmpty()) {
				index.markFailed(filingId, "The document has no parsed text", clock.instant());
				return;
			}
			List<Chunker.Chunk> chunks = Chunker.chunk(sections);
			List<float[]> vectors = null;
			String detail = null;
			if (embeddings.available()) {
				try {
					vectors = embeddings.embedDocuments(chunks.stream().map(c -> embedText(c.sectionTitle(), c.text())).toList());
				}
				catch (RuntimeException ex) {
					log.warn("Embedding filing {} failed, indexing for keyword search only: {}", filingId, ex.getMessage());
					detail = "Semantic search unavailable: " + ex.getMessage();
				}
			}
			index.replaceChunks(filingId, chunks, vectors, embeddings.modelName(), detail, clock.instant());
			if (vectors == null && embeddings.available()) {
				requestEmbedding(jobs, filingId, FIRST_EMBEDDING_DELAY);
			}
			log.info("Indexed filing {}: {} chunks ({})", filingId, chunks.size(),
					vectors == null ? "full-text only" : "embedded with " + embeddings.modelName());
		}

		@Override
		public void onPermanentFailure(Job job, Throwable cause) {
			index.markFailed(filingId(job), "Indexing failed: " + cause.getMessage(), clock.instant());
		}

		private long filingId(Job job) {
			return json.readTree(job.payload()).path("filingId").asLong();
		}

	}

	/** Adds embeddings to a filing indexed for keyword search only. */
	@Component
	static class EmbedJob implements JobHandler {

		static final String TYPE = "research.embed";

		private static final Logger log = LoggerFactory.getLogger(EmbedJob.class);

		private final IndexRepository index;

		private final EmbeddingModel embeddings;

		private final JsonMapper json;

		private final Clock clock;

		EmbedJob(IndexRepository index, EmbeddingModel embeddings, JsonMapper json, Clock clock) {
			this.index = index;
			this.embeddings = embeddings;
			this.json = json;
			this.clock = clock;
		}

		@Override
		public String type() {
			return TYPE;
		}

		@Override
		public boolean transactional() {
			return false;
		}

		@Override
		public void handle(Job job) {
			long filingId = filingId(job);
			if (!embeddings.available()) {
				return;
			}
			List<UnembeddedChunk> chunks = index.unembeddedChunks(filingId);
			List<float[]> vectors = chunks.isEmpty() ? List.of()
					: embeddings.embedDocuments(chunks.stream().map(c -> embedText(c.sectionTitle(), c.text())).toList());
			index.storeEmbeddings(filingId, chunks.stream().map(UnembeddedChunk::id).toList(), vectors,
					embeddings.modelName(), clock.instant());
			log.info("Embedded {} chunks of filing {} with {}", chunks.size(), filingId, embeddings.modelName());
		}

		/** The filing stays searchable by keyword; embedding is tried again later. */
		@Override
		public void onPermanentFailure(Job job, Throwable cause) {
			index.markEmbeddingFailed(filingId(job), "Semantic search unavailable: " + cause.getMessage(), clock.instant());
		}

		private long filingId(Job job) {
			return json.readTree(job.payload()).path("filingId").asLong();
		}

	}

}
