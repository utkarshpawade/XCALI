package com.drawapp.backend.auth;

/** Email validation shared by signup and signin. */
final class Emails {

	/** The regex zod's {@code .email()} used, so the same addresses pass. */
	static final String PATTERN = "^(?!\\.)(?!.*\\.\\.)([A-Z0-9_'+\\-\\.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\\-]*\\.)+[A-Z]{2,}$";

	static final String MESSAGE = "A valid email address is required";

	private Emails() {
	}

}
