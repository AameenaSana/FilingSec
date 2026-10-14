package com.filinglens.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.filinglens.platform.config.FilingLensProperties;

/**
 * {@link BlobStore} backed by a local directory (a Docker volume in deployment).
 * Writes go to a temporary file and are moved into place, so readers never see a
 * partially written blob. Keys are strictly validated to prevent path traversal.
 */
@Component
public class FileSystemBlobStore implements BlobStore {

	private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

	private final Path root;

	@Autowired
	public FileSystemBlobStore(FilingLensProperties properties) {
		this(properties.storage().rootPath());
	}

	FileSystemBlobStore(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	@Override
	public StoredBlob put(String key, InputStream content) {
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				long size;
				try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
					size = content.transferTo(out);
				}
				move(temp, target);
				return new StoredBlob(key, HexFormat.of().formatHex(digest.digest()), size);
			}
			finally {
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to store blob " + key, ex);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

	@Override
	public Optional<InputStream> open(String key) {
		Path path = resolve(key);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Files.newInputStream(path));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to open blob " + key, ex);
		}
	}

	@Override
	public boolean exists(String key) {
		return Files.isRegularFile(resolve(key));
	}

	private Path resolve(String key) {
		if (key == null || !VALID_KEY.matcher(key).matches() || key.contains("..")) {
			throw new IllegalArgumentException("Invalid blob key: " + key);
		}
		Path resolved = root.resolve(key).normalize();
		if (!resolved.startsWith(root)) {
			throw new IllegalArgumentException("Invalid blob key: " + key);
		}
		return resolved;
	}

	private static void move(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException ex) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

}
