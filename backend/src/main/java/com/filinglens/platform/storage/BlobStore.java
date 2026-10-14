package com.filinglens.platform.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * Content-addressed-by-key storage for immutable raw artifacts such as SEC filing
 * documents. Keys are relative, slash-separated paths, e.g.
 * {@code edgar/0000320193/000032019324000123/aapl-20240928.htm}.
 */
public interface BlobStore {

	/** Stores the content under the key, replacing any existing blob atomically. */
	StoredBlob put(String key, InputStream content);

	/** Opens the blob for reading; the caller must close the stream. */
	Optional<InputStream> open(String key);

	boolean exists(String key);

	record StoredBlob(String key, String sha256, long sizeBytes) {
	}

}
