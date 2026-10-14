package com.filinglens.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for expected, client-visible failures. The {@code code} is a stable,
 * machine-readable identifier exposed in the ProblemDetail {@code type} URI; the
 * message is safe to show to users and must never contain secrets or internals.
 */
public abstract class DomainException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	protected DomainException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

}
