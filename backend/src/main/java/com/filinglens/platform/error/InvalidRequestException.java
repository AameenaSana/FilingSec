package com.filinglens.platform.error;

import org.springframework.http.HttpStatus;

/** A well-formed request that cannot be fulfilled, e.g. an expired token. */
public class InvalidRequestException extends DomainException {

	public InvalidRequestException(String code, String message) {
		super(HttpStatus.BAD_REQUEST, code, message);
	}

}
