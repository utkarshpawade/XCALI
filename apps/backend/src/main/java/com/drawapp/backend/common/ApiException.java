package com.drawapp.backend.common;

import org.springframework.http.HttpStatus;

/** An error with a status and a message that is safe to show the user. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	public ApiException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

}
