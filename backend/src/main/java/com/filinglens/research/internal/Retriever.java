package com.filinglens.research.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.filinglens.research.internal.IndexRepository.ChunkRow;

/**
 * Hybrid retrieval: semantic (vector) and keyword (full-text) results are merged
 * with reciprocal rank fusion, which needs no score calibration between the two.
 * Without an embedding provider, or if embedding the query fails, retrieval is
 * keyword-only.
 */
@Component
class Retriever {

	/** Standard RRF damping constant. */
	static final int RRF_K = 60;

	private static final Logger log = LoggerFactory.getLogger(Retriever.class);

	private final IndexRepository index;

	private final EmbeddingModel embeddings;

	Retriever(IndexRepository index, EmbeddingModel embeddings) {
		this.index = index;
		this.embeddings = embeddings;
	}

	/**
	 * @param vectorRank 1-based rank in semantic results, or {@code null}
	 * @param textRank 1-based rank in keyword results, or {@code null}
	 */
	record Retrieved(ChunkRow chunk, double score, Integer vectorRank, Integer textRank) {
	}

	List<Retrieved> retrieve(List<Long> filingIds, String query, int limit) {
		int pool = limit * 3;
		List<Long> textHits = index.textSearch(filingIds, query, pool);
		List<Long> vectorHits = List.of();
		if (embeddings.available()) {
			try {
				vectorHits = index.vectorSearch(filingIds, embeddings.embedQuery(query), pool);
			}
			catch (RuntimeException ex) {
				log.warn("Semantic retrieval unavailable, using keyword results only: {}", ex.getMessage());
			}
		}
		return fuse(vectorHits, textHits, limit);
	}

	private List<Retrieved> fuse(List<Long> vectorHits, List<Long> textHits, int limit) {
		Map<Long, Double> scores = new HashMap<>();
		Map<Long, Integer> vectorRanks = new HashMap<>();
		Map<Long, Integer> textRanks = new HashMap<>();
		for (int i = 0; i < vectorHits.size(); i++) {
			scores.merge(vectorHits.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
			vectorRanks.put(vectorHits.get(i), i + 1);
		}
		for (int i = 0; i < textHits.size(); i++) {
			scores.merge(textHits.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
			textRanks.put(textHits.get(i), i + 1);
		}
		List<Long> ranked = scores.entrySet()
			.stream()
			.sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(limit)
			.map(Map.Entry::getKey)
			.toList();
		List<Retrieved> results = new ArrayList<>();
		for (ChunkRow chunk : index.load(ranked)) {
			results.add(new Retrieved(chunk, scores.get(chunk.id()), vectorRanks.get(chunk.id()), textRanks.get(chunk.id())));
		}
		return results;
	}

}
