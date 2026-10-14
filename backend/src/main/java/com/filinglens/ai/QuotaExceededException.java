package com.filinglens.ai;

import org.springframework.http.HttpStatus;

import com.filinglens.platform.error.DomainException;

public class QuotaExceededException extends DomainException {

	public QuotaExceededException() {
		super(HttpStatus.TOO_MANY_REQUESTS, "quota-exceeded",
				"You have reached today's AI usage limit. It resets at midnight UTC.");
	}

}
