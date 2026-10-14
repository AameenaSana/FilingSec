package com.filinglens.research.internal;

import java.util.List;

/** Port for text embeddings used in retrieval. */
interface EmbeddingModel {

	/** False when no provider is configured; retrieval then uses full-text search only. */
	boolean available();

	String modelName();

	List<float[]> embedDocuments(List<String> texts);

	float[] embedQuery(String text);

}
