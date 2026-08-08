package com.drawapp.backend.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.mock.env.MockEnvironment;

import com.drawapp.backend.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class JwtServiceTests {

	private static final String SECRET = "a-secret-that-is-at-least-thirty-two-bytes-long";

	@Test
	void issuedTokensVerify() {
		JwtService jwt = service(SECRET, Duration.ofDays(7));

		assertThat(jwt.verify(jwt.issue("user-1"))).contains("user-1");
	}

	@Test
	void issuedTokensHaveTheShapeTheNodeApiProduced() {
		String token = service(SECRET, Duration.ofDays(7)).issue("user-1");
		String[] parts = token.split("\\.");
		JsonMapper json = JsonMapper.builder().build();
		JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
		JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));

		assertThat(header.get("alg").stringValue()).isEqualTo("HS256");
		assertThat(header.get("typ").stringValue()).isEqualTo("JWT");
		assertThat(payload.get("userId").stringValue()).isEqualTo("user-1");
		assertThat(payload.get("exp").longValue() - payload.get("iat").longValue()).isEqualTo(7 * 24 * 3600);
	}

	@Test
	void tokensSignedWithAnotherSecretAreRejected() {
		String token = service(SECRET, Duration.ofDays(7)).issue("user-1");

		assertThat(service(SECRET + "-other", Duration.ofDays(7)).verify(token)).isEmpty();
	}

	@Test
	void expiredTokensAreRejected() throws Exception {
		long now = Instant.now().getEpochSecond();
		JwtService jwt = service(SECRET, Duration.ofDays(7));

		assertThat(jwt.verify(sign("{\"userId\":\"user-1\",\"iat\":" + (now - 600) + ",\"exp\":" + (now + 600) + "}")))
			.contains("user-1");
		assertThat(jwt.verify(sign("{\"userId\":\"user-1\",\"iat\":" + (now - 600) + ",\"exp\":" + (now - 300) + "}")))
			.isEmpty();
	}

	@Test
	void tokensWithoutAUserIdAreRejected() throws Exception {
		long now = Instant.now().getEpochSecond();

		assertThat(service(SECRET, Duration.ofDays(7)).verify(sign("{\"iat\":" + now + ",\"exp\":" + (now + 600) + "}")))
			.isEmpty();
	}

	@Test
	void garbageIsRejected() {
		assertThat(service(SECRET, Duration.ofDays(7)).verify("not.a.token")).isEmpty();
	}

	@Test
	void bearerPrefixIsOptional() {
		assertThat(JwtService.tokenFromAuthorizationHeader("Bearer abc ")).isEqualTo("abc");
		assertThat(JwtService.tokenFromAuthorizationHeader("abc")).isEqualTo("abc");
		assertThat(JwtService.tokenFromAuthorizationHeader("Bearer  ")).isNull();
		assertThat(JwtService.tokenFromAuthorizationHeader(null)).isNull();
	}

	@Test
	void developmentFallsBackToAPlaceholderSecret() {
		assertThat(JwtService.resolveSecret("", false)).isEqualTo(JwtService.DEV_SECRET);
		assertThat(JwtService.resolveSecret(null, false)).isEqualTo(JwtService.DEV_SECRET);
	}

	@Test
	void productionRequiresARealSecret() {
		assertThatIllegalStateException().isThrownBy(() -> JwtService.resolveSecret("", true))
			.withMessageContaining("JWT_SECRET is not set");
		assertThatIllegalStateException().isThrownBy(() -> JwtService.resolveSecret(JwtService.DEV_SECRET, true))
			.withMessageContaining("development placeholder");
	}

	@Test
	void shortSecretsAreRejected() {
		assertThatIllegalStateException().isThrownBy(() -> JwtService.resolveSecret("too-short", false))
			.withMessageContaining("at least 32 bytes");
	}

	/** Signs a payload the way jsonwebtoken does: HS256 over base64url parts. */
	private static String sign(String payloadJson) throws Exception {
		Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
		String unsigned = base64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
				+ "." + base64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return unsigned + "." + base64.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
	}

	private static JwtService service(String secret, Duration expiresIn) {
		AppProperties properties = new AppProperties(new AppProperties.Jwt(secret, expiresIn), List.of("*"));
		return new JwtService(properties, new MockEnvironment());
	}

}
