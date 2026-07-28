package com.drawapp.backend.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings read from the environment; see application.yml for the variable
 * each one comes from.
 *
 * @param jwt token signing settings
 * @param allowedOrigins browser origins allowed to call the API and open
 * sockets. {@code *} allows any origin.
 */
@ConfigurationProperties("app")
public record AppProperties(@DefaultValue Jwt jwt, @DefaultValue("*") List<String> allowedOrigins) {

	public AppProperties {
		allowedOrigins = allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
	}

	public boolean allowsAnyOrigin() {
		return allowedOrigins.isEmpty() || allowedOrigins.contains("*");
	}

	/**
	 * @param secret HMAC secret shared by everything that verifies tokens
	 * @param expiresIn token lifetime, e.g. {@code 7d} or {@code 12h}
	 */
	public record Jwt(String secret, @DefaultValue("7d") Duration expiresIn) {
	}

}
