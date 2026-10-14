package com.filinglens.platform.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends DomainException {

	public ConflictException(String code, String message) {
		super(HttpStatus.CONFLICT, code, message);
	}

}
