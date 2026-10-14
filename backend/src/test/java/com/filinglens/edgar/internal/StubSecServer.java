package com.filinglens.edgar.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/** A local HTTP server standing in for sec.gov, with scripted responses per path. */
class StubSecServer implements AutoCloseable {

	record Response(int status, byte[] body, Map<String, String> headers, boolean gzip) {

		static Response ok(byte[] body) {
			return new Response(200, body, Map.of("Content-Type", "application/json"), true);
		}

		static Response status(int status) {
			return new Response(status, new byte[0], Map.of(), false);
		}

	}

	record RecordedRequest(String path, Headers headers) {
	}

	private final HttpServer server;

	private final Map<String, Deque<Response>> scripts = new ConcurrentHashMap<>();

	private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

	StubSecServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			requests.add(new RecordedRequest(path, exchange.getRequestHeaders()));
			Deque<Response> script = scripts.get(path);
			Response response = script == null || script.isEmpty() ? Response.status(404)
					: script.size() > 1 ? script.poll() : script.peek();
			response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
			byte[] body = response.body();
			if (response.gzip() && body.length > 0) {
				exchange.getResponseHeaders().add("Content-Encoding", "gzip");
				body = gzip(body);
			}
			exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	/** Responses are served in order; the last one repeats. */
	StubSecServer on(String path, Response... responses) {
		scripts.put(path, new ArrayDeque<>(List.of(responses)));
		return this;
	}

	URI baseUri() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	List<RecordedRequest> requests() {
		return List.copyOf(requests);
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private static byte[] gzip(byte[] body) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
				gzip.write(body);
			}
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
