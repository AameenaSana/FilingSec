package com.filinglens.platform.error;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends DomainException {

	public UnauthorizedException(String code, String message) {
		super(HttpStatus.UNAUTHORIZED, code, message);
	}

}
