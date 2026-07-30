package com.drawapp.backend.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** @param username the email address the account was created with */
public record SigninRequest(
		@NotNull(message = "Required") @Pattern(regexp = Emails.PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE,
				message = Emails.MESSAGE) String username,
		@NotNull(message = "Required") @Size(min = 1, message = "Password is required") String password) {
}
