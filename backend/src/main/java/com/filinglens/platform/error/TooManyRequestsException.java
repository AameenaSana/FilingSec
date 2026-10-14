package com.filinglens.platform.error;

import org.springframework.http.HttpStatus;

public class TooManyRequestsException extends DomainException {

	public TooManyRequestsException() {
		super(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", "Too many requests. Please wait and try again.");
	}

}
