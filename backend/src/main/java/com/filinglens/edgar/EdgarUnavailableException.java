package com.filinglens.edgar;

import org.springframework.http.HttpStatus;

import com.filinglens.platform.error.DomainException;

/** SEC EDGAR could not be reached, refused the request, or traffic is paused. */
public class EdgarUnavailableException extends DomainException {

	public EdgarUnavailableException(String message) {
		super(HttpStatus.SERVICE_UNAVAILABLE, "edgar-unavailable", message);
	}

}
