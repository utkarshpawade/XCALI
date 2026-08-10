package com.drawapp.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.drawapp.backend.ApiClient.Response;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts against a database that Prisma created and the Node backends filled,
 * the state every existing deployment is in. Fixtures were produced by the
 * Node libraries themselves (jsonwebtoken, bcryptjs).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "app.jwt.secret=" + PrismaDatabaseTests.NODE_JWT_SECRET)
class PrismaDatabaseTests {

	static final String NODE_JWT_SECRET = "test-secret-shared-with-the-node-backend-0123456789";

	private static final String LEGACY_USER_ID = "5b0e3c4a-8f7e-4d51-9a55-0c1f2b3d4e5f";

	/** jsonwebtoken.sign({ userId }, NODE_JWT_SECRET, { expiresIn: "100y" }) */
	private static final String NODE_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiI1YjBlM2M0YS04ZjdlLTRkNTEtOWE1NS0wYzFmMmIzZDRlNWYiLCJpYXQiOjE3OTA1MDMwNTgsImV4cCI6NDk0NjI2MzA1OH0.pOleoTkL1HXvW56_13zEOESNjSFvSr4QmRB7Q8C-vcE";

	/** The same, with an exp an hour in the past. */
	private static final String EXPIRED_NODE_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiI1YjBlM2M0YS04ZjdlLTRkNTEtOWE1NS0wYzFmMmIzZDRlNWYiLCJleHAiOjE3OTA0OTk0NTgsImlhdCI6MTc5MDUwMzA1OH0.w7XPk_qPQAuzUSbQ-4HvddD9KIE8f7aT7_xFeFML8U0";

	/** bcryptjs.hashSync("hunter22", 10) */
	private static final String BCRYPTJS_HASH = "$2b$10$tr0s2Iqa4aCiiBnZlxROa.iLqH56f5M9WMqDgZDv/gQC2SUq5GKpq";

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private DataSource dataSource;

	private ApiClient api;

	@DynamicPropertySource
	static void prismaDatabase(DynamicPropertyRegistry registry) throws IOException {
		DataSource database = TestDatabase.dataSource("prisma_legacy");
		TestDatabase.execute(database,
				new ClassPathResource("prisma/migrations.sql").getContentAsString(StandardCharsets.UTF_8));
		TestDatabase.execute(database, """
				INSERT INTO "User" ("id", "email", "password", "name")
				VALUES ('%s', 'legacy@example.com', '%s', 'Legacy User');
				INSERT INTO "Room" ("slug", "createdAt", "adminId")
				VALUES ('legacy-board', '2025-01-12 15:09:13.123', '%s');
				INSERT INTO "Chat" ("roomId", "message", "userId")
				VALUES (1, '{"shape":{"type":"circle","centerX":1,"centerY":2,"radius":3}}', '%s');
				""".formatted(LEGACY_USER_ID, BCRYPTJS_HASH, LEGACY_USER_ID, LEGACY_USER_ID));
		TestDatabase.register(registry, "prisma_legacy");
	}

	@BeforeEach
	void createClient() {
		this.api = new ApiClient(this.port);
	}

	@Test
	void flywayBaselinesTheExistingSchemaInsteadOfRecreatingIt() {
		List<String> history = JdbcClient.create(this.dataSource)
			.sql("SELECT type || ':' || version FROM flyway_schema_history ORDER BY installed_rank")
			.query(String.class)
			.list();

		assertThat(history).containsExactly("BASELINE:1");
	}

	@Test
	void passwordsHashedByBcryptjsStillSignIn() {
		Response response = this.api.post("/signin", Map.of("username", "legacy@example.com", "password", "hunter22"),
				null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body().get("user").get("id").stringValue()).isEqualTo(LEGACY_USER_ID);
	}

	@Test
	void tokensIssuedByTheNodeApiStayValid() {
		Response me = this.api.get("/me", NODE_TOKEN);

		assertThat(me.status()).isEqualTo(200);
		assertThat(me.body().get("user").get("email").stringValue()).isEqualTo("legacy@example.com");

		Response expired = this.api.get("/me", EXPIRED_NODE_TOKEN);
		assertThat(expired.status()).isEqualTo(401);
		assertThat(expired.message()).isEqualTo("Invalid or expired token");
	}

	@Test
	void existingRoomsAndShapesReadBackUnchanged() {
		JsonNode rooms = this.api.get("/rooms", NODE_TOKEN).body().get("rooms");

		assertThat(rooms.size()).isEqualTo(1);
		assertThat(rooms.get(0).get("slug").stringValue()).isEqualTo("legacy-board");
		// Stored as UTC wall-clock time by Prisma; must not shift with the JVM zone.
		assertThat(rooms.get(0).get("createdAt").stringValue()).isEqualTo("2025-01-12T15:09:13.123Z");

		JsonNode messages = this.api.get("/chats/1", NODE_TOKEN).body().get("messages");
		assertThat(messages.size()).isEqualTo(1);
		assertThat(messages.get(0).get("userId").stringValue()).isEqualTo(LEGACY_USER_ID);
	}

	@Test
	void newRowsFitAlongsideTheOldOnes() {
		Response signup = this.api.post("/signup",
				Map.of("username", "newcomer@example.com", "password", "secret1", "name", "New"), null);
		assertThat(signup.status()).isEqualTo(201);

		Response room = this.api.post("/room", Map.of("name", "second-board"), NODE_TOKEN);
		assertThat(room.status()).isEqualTo(201);
		assertThat(room.body().get("roomId").intValue()).as("sequence continues after Prisma's rows").isEqualTo(2);
	}

}
