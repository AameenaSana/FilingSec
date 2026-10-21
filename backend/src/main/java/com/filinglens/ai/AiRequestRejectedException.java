package com.filinglens.ai;

/**
 * The AI provider refused the request for a reason retrying cannot fix: billing,
 * an invalid or revoked API key, missing permissions, or an invalid request. The
 * message describes the operator's account, so it is for logs only and must not
 * be shown to users.
 */
public class AiRequestRejectedException extends RuntimeException {

	private final int statusCode;

	public AiRequestRejectedException(int statusCode, String message, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
	}

	public int statusCode() {
		return statusCode;
	}

}
