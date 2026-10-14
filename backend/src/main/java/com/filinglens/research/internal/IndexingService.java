package com.filinglens.research.internal;

import java.time.Clock;
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
import com.filinglens.platform.jobs.Job;
import com.filinglens.platform.jobs.JobHandler;
import com.filinglens.platform.jobs.JobQueue;

import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the retrieval index in step with parsed documents: every parsed filing
 * is chunked and (if an embedding provider is configured) embedded.
 */
@Service
class IndexingService {

	enum Readiness {

		PREPARING, READY, UNAVAILABLE

	}

	private final IndexRepository index;

	private final DocumentContent documents;

	private final JobQueue jobs;

	private final Clock clock;

	IndexingService(IndexRepository index, DocumentContent documents, JobQueue jobs, Clock clock) {
		this.index = index;
		this.documents = documents;
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
		if (state != null && "INDEXED".equals(state.status())) {
			return Readiness.READY;
		}
		if (state != null && "INDEXING".equals(state.status())) {
			return Readiness.PREPARING;
		}
		return switch (documents.state(filingId)) {
			case UNAVAILABLE -> Readiness.UNAVAILABLE;
			case PARSED -> {
				// Parsed before this filing was ever indexed, or indexing failed: (re)index.
				requestIndex(filingId, state == null || state.parserVersion() == null ? 1 : state.parserVersion());
				yield Readiness.PREPARING;
			}
			case PENDING -> {
				documents.prepare(filingId);
				yield Readiness.PREPARING;
			}
		};
	}

	/** Builds a filing's chunks and embeddings. */
	@Component
	static class IndexJob implements JobHandler {

		static final String TYPE = "research.index";

		private static final Logger log = LoggerFactory.getLogger(IndexJob.class);

		private final IndexRepository index;

		private final DocumentContent documents;

		private final EmbeddingModel embeddings;

		private final JsonMapper json;

		private final Clock clock;

		IndexJob(IndexRepository index, DocumentContent documents, EmbeddingModel embeddings, JsonMapper json,
				Clock clock) {
			this.index = index;
			this.documents = documents;
			this.embeddings = embeddings;
			this.json = json;
			this.clock = clock;
		}

		@Override
		public String type() {
			return TYPE;
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
			if (embeddings.available()) {
				// The section title gives each chunk context the text alone may lack.
				vectors = embeddings.embedDocuments(chunks.stream().map(c -> c.sectionTitle() + "\n\n" + c.text()).toList());
			}
			index.replaceChunks(filingId, chunks, vectors, embeddings.modelName(), clock.instant());
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

}
