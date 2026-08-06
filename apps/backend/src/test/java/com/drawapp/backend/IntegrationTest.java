package com.drawapp.backend;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.drawapp.backend.ApiClient.Response;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole application on a random port against a fresh Postgres
 * database migrated by Flyway. Subclasses share one application context and
 * one database, so tests create their own users and rooms with unique names.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "app.jwt.secret=test-secret-for-the-integration-tests-0123456789",
				"app.allowed-origins=http://localhost:3000", "app.ws.heartbeat-interval=500ms" })
abstract class IntegrationTest {

	@Value("${local.server.port}")
	protected int port;

	protected ApiClient api;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		TestDatabase.register(registry, "integration");
	}

	@BeforeEach
	void createClient() {
		this.api = new ApiClient(this.port);
	}

	protected static String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** Signs up a fresh user and returns their token. */
	protected String signUp() {
		Response response = this.api.post("/signup",
				Map.of("username", unique("user") + "@example.com", "password", "secret-password", "name", "Ada"),
				null);
		assertThat(response.status()).isEqualTo(201);
		return response.body().get("token").stringValue();
	}

	protected int createRoom(String token, String name) {
		Response response = this.api.post("/room", Map.of("name", name), token);
		assertThat(response.status()).isEqualTo(201);
		return response.body().get("roomId").intValue();
	}

	protected SocketClient connect(String token) {
		return SocketClient.connect(this.port, "?token=" + token);
	}

}
