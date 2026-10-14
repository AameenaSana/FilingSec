package com.filinglens.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileSystemBlobStoreTest {

	@TempDir
	Path root;

	@Test
	void storesAndReadsBackContentWithChecksum() throws IOException {
		FileSystemBlobStore store = new FileSystemBlobStore(root);
		byte[] content = "<html>10-K</html>".getBytes(StandardCharsets.UTF_8);

		BlobStore.StoredBlob stored = store.put("edgar/0000320193/doc.htm", new ByteArrayInputStream(content));

		assertThat(stored.sizeBytes()).isEqualTo(content.length);
		assertThat(stored.sha256()).hasSize(64);
		assertThat(store.exists("edgar/0000320193/doc.htm")).isTrue();
		try (InputStream in = store.open("edgar/0000320193/doc.htm").orElseThrow()) {
			assertThat(in.readAllBytes()).isEqualTo(content);
		}
	}

	@Test
	void replacesExistingBlobAndLeavesNoTempFiles() throws IOException {
		FileSystemBlobStore store = new FileSystemBlobStore(root);
		store.put("a/b.txt", new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)));
		store.put("a/b.txt", new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)));

		try (InputStream in = store.open("a/b.txt").orElseThrow()) {
			assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("two");
		}
		try (var files = Files.list(root.resolve("a"))) {
			assertThat(files).extracting(p -> p.getFileName().toString()).containsExactly("b.txt");
		}
	}

	@Test
	void missingBlobIsEmpty() {
		assertThat(new FileSystemBlobStore(root).open("nope.htm")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "../escape.txt", "a/../../escape.txt", "/absolute.txt", "a\\b.txt", "", "a//b.txt",
			"C:/x.txt" })
	void rejectsUnsafeKeys(String key) {
		FileSystemBlobStore store = new FileSystemBlobStore(root);
		assertThatIllegalArgumentException().isThrownBy(() -> store.exists(key));
	}

}
