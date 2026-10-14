package com.filinglens.platform.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects API request bodies larger than {@link #MAX_BYTES}. Every API request is
 * small JSON, so this bounds the memory a single request can make the server use
 * before validation runs. Declared oversized bodies get 413; bodies without a
 * length (chunked) fail while being read once they pass the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

	public static final long MAX_BYTES = 256 * 1024;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > MAX_BYTES) {
			response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter()
				.write("{\"type\":\"urn:filinglens:problem:payload-too-large\",\"title\":\"Content Too Large\","
						+ "\"status\":413,\"detail\":\"The request body is too large\"}");
			return;
		}
		chain.doFilter(request.getContentLengthLong() < 0 ? new LimitedRequest(request) : request, response);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	private static final class LimitedRequest extends HttpServletRequestWrapper {

		private ServletInputStream limited;

		LimitedRequest(HttpServletRequest request) {
			super(request);
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (limited == null) {
				limited = new LimitedInputStream(super.getInputStream());
			}
			return limited;
		}

	}

	private static final class LimitedInputStream extends ServletInputStream {

		private final ServletInputStream in;

		private long read;

		LimitedInputStream(ServletInputStream in) {
			this.in = in;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = in.read(buffer, offset, length);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) throws IOException {
			read += n;
			if (read > MAX_BYTES) {
				throw new IOException("Request body exceeds " + MAX_BYTES + " bytes");
			}
		}

		@Override
		public boolean isFinished() {
			return in.isFinished();
		}

		@Override
		public boolean isReady() {
			return in.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			in.setReadListener(listener);
		}

	}

}
