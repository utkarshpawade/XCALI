package com.drawapp.backend.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Messages match the zod schema this replaces, since the frontends show them
 * verbatim.
 *
 * @param username the email address to sign in with
 */
public record SignupRequest(
		@NotNull(message = "Required") @Pattern(regexp = Emails.PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE,
				message = Emails.MESSAGE) String username,
		@NotNull(message = "Required") @Size(min = 6,
				message = "Password must be at least 6 characters") String password,
		@NotNull(message = "Required") @Size(min = 1, message = "Name is required") @Size(max = 50,
				message = "String must contain at most 50 character(s)") String name) {
}
