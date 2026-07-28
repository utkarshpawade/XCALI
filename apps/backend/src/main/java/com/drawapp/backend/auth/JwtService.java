package com.drawapp.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.drawapp.backend.config.AppProperties;

/**
 * Issues and verifies HS256 tokens carrying a {@code userId} claim - the same
 * format the Node API issued, so existing sessions stay valid across the
 * migration as long as JWT_SECRET is unchanged.
 */
@Service
public class JwtService {

	private static final Logger log = LoggerFactory.getLogger(JwtService.class);

	/** Used when JWT_SECRET is unset outside production. */
	static final String DEV_SECRET = "dev-only-insecure-secret-do-not-use-in-production";

	/** RFC 7518 requires an HS256 key of at least 256 bits. */
	private static final int MIN_SECRET_BYTES = 32;

	private static final String USER_ID_CLAIM = "userId";

	private final JwtEncoder encoder;

	private final JwtDecoder decoder;

	private final Duration expiresIn;

	public JwtService(AppProperties properties, Environment environment) {
		String secret = resolveSecret(properties.jwt().secret(), environment.matchesProfiles("prod"));
		SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

		this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();

		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(JwtValidators
			.createDefaultWithValidators(new JwtClaimValidator<Object>(USER_ID_CLAIM, Objects::nonNull)));
		this.decoder = decoder;

		this.expiresIn = properties.jwt().expiresIn();
	}

	public String issue(String userId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.claim(USER_ID_CLAIM, userId)
			.issuedAt(now)
			.expiresAt(now.plus(this.expiresIn))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		return this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	/** Returns the user id, or empty when the token is malformed, forged or expired. */
	public Optional<String> verify(String token) {
		try {
			return Optional.of(String.valueOf(this.decoder.decode(token).getClaims().get(USER_ID_CLAIM)));
		}
		catch (JwtException ex) {
			return Optional.empty();
		}
	}

	/** Accepts {@code Bearer <token>} or a bare token; null when there is none. */
	public static @Nullable String tokenFromAuthorizationHeader(@Nullable String header) {
		if (header == null) {
			return null;
		}
		String token = (header.startsWith("Bearer ") ? header.substring("Bearer ".length()) : header).strip();
		return token.isEmpty() ? null : token;
	}

	static String resolveSecret(@Nullable String configured, boolean production) {
		if (configured == null || configured.isBlank()) {
			if (production) {
				throw new IllegalStateException(
						"JWT_SECRET is not set. Refusing to start in production with an insecure default.");
			}
			log.warn("JWT_SECRET is not set - falling back to an insecure development secret.");
			return DEV_SECRET;
		}
		if (production && configured.equals(DEV_SECRET)) {
			throw new IllegalStateException(
					"JWT_SECRET is set to the development placeholder. Set a strong secret in production.");
		}
		if (configured.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES
					+ " bytes for HS256. Generate one with: openssl rand -hex 48");
		}
		return configured;
	}

}
