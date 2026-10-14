package com.filinglens.ai;

import org.springframework.http.HttpStatus;

import com.filinglens.platform.error.DomainException;

public class AiNotConfiguredException extends DomainException {

	public AiNotConfiguredException() {
		super(HttpStatus.SERVICE_UNAVAILABLE, "ai-not-configured", "AI features are not configured on this server.");
	}

}
