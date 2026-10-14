package com.filinglens.platform.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends DomainException {

	public NotFoundException(String resource, Object id) {
		super(HttpStatus.NOT_FOUND, "not-found", "%s '%s' was not found".formatted(resource, id));
	}

}
