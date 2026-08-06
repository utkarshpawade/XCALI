package com.drawapp.backend;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import com.drawapp.backend.ApiClient.Response;

import static org.assertj.core.api.Assertions.assertThat;

/** The REST contract the frontends depend on: routes, bodies, statuses, messages. */
class RestApiTests extends IntegrationTest {

	@Test
	void healthNeedsNoToken() {
		Response response = this.api.get("/health", null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body().get("status").stringValue()).isEqualTo("ok");
		assertThat(response.body().get("uptime").isNumber()).isTrue();
		assertThat(response.body().get("connections").isNumber()).isTrue();
	}

	@Test
	void signupReturnsTheUserAndAWorkingToken() {
		String email = unique("ada") + "@example.com";
		Response signup = this.api.post("/signup", Map.of("username", email, "password", "secret1", "name", "Ada"),
				null);

		assertThat(signup.status()).isEqualTo(201);
		JsonNode user = signup.body().get("user");
		assertThat(signup.body().get("userId").stringValue()).isEqualTo(user.get("id").stringValue());
		assertThat(user.get("email").stringValue()).isEqualTo(email);
		assertThat(user.get("name").stringValue()).isEqualTo("Ada");
		assertThat(user.has("password")).isFalse();

		Response me = this.api.get("/me", signup.body().get("token").stringValue());
		assertThat(me.status()).isEqualTo(200);
		assertThat(me.body().get("user")).isEqualTo(user);
	}

	@Test
	void signupRejectsADuplicateEmail() {
		String email = unique("dup") + "@example.com";
		Map<String, String> body = Map.of("username", email, "password", "secret1", "name", "Ada");
		assertThat(this.api.post("/signup", body, null).status()).isEqualTo(201);

		Response again = this.api.post("/signup", body, null);

		assertThat(again.status()).isEqualTo(409);
		assertThat(again.message()).isEqualTo("An account with this email already exists");
	}

	@Test
	void signupValidationReportsTheFirstProblemOfEachFieldInOrder() {
		Response response = this.api.post("/signup",
				Map.of("username", "not-an-email", "password", "123", "name", "x".repeat(51)), null);

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.message()).isEqualTo("Incorrect inputs");
		assertThat(fieldNames(response.body().get("errors"))).containsExactly("username", "password", "name");
		assertThat(response.body().get("errors").get("username").stringValue())
			.isEqualTo("A valid email address is required");
		assertThat(response.body().get("errors").get("password").stringValue())
			.isEqualTo("Password must be at least 6 characters");
		assertThat(response.body().get("errors").get("name").stringValue())
			.isEqualTo("String must contain at most 50 character(s)");
	}

	@Test
	void missingFieldsAreRequired() {
		Response response = this.api.post("/signup", Map.of("name", ""), null);

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.body().get("errors").get("username").stringValue()).isEqualTo("Required");
		assertThat(response.body().get("errors").get("password").stringValue()).isEqualTo("Required");
		assertThat(response.body().get("errors").get("name").stringValue()).isEqualTo("Name is required");
	}

	@Test
	void malformedJsonIsABadRequest() {
		Response response = this.api.post("/signin", "{not json", null);

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.message()).isEqualTo("Incorrect inputs");
	}

	@Test
	void signinChecksThePassword() {
		String email = unique("signin") + "@example.com";
		this.api.post("/signup", Map.of("username", email, "password", "right-password", "name", "Ada"), null);

		Response ok = this.api.post("/signin", Map.of("username", email, "password", "right-password"), null);
		assertThat(ok.status()).isEqualTo(200);
		assertThat(ok.body().get("token").stringValue()).isNotBlank();
		assertThat(ok.body().get("user").get("email").stringValue()).isEqualTo(email);

		Response wrongPassword = this.api.post("/signin", Map.of("username", email, "password", "wrong"), null);
		assertThat(wrongPassword.status()).isEqualTo(403);
		assertThat(wrongPassword.message()).isEqualTo("Incorrect email or password");

		Response unknownUser = this.api.post("/signin",
				Map.of("username", unique("nobody") + "@example.com", "password", "whatever"), null);
		assertThat(unknownUser.status()).isEqualTo(403);
		assertThat(unknownUser.message()).isEqualTo("Incorrect email or password");
	}

	@Test
	void aStaleTokenDoesNotBreakSignin() {
		String email = unique("stale") + "@example.com";
		this.api.post("/signup", Map.of("username", email, "password", "secret1", "name", "Ada"), null);

		// The frontends attach whatever token is in localStorage to every call.
		Response response = this.api.post("/signin", Map.of("username", email, "password", "secret1"),
				"expired.or.garbage");

		assertThat(response.status()).isEqualTo(200);
	}

	@Test
	void protectedRoutesExplainWhyTheyRejectARequest() {
		Response noToken = this.api.get("/me", null);
		assertThat(noToken.status()).isEqualTo(401);
		assertThat(noToken.message()).isEqualTo("Authentication required");

		Response badToken = this.api.get("/me", "not-a-jwt");
		assertThat(badToken.status()).isEqualTo(401);
		assertThat(badToken.message()).isEqualTo("Invalid or expired token");
	}

	@Test
	void aTokenIsAcceptedWithoutTheBearerPrefix() {
		String token = signUp();

		Response response = this.api.send(this.api.request("/me", null).header("Authorization", token).GET());

		assertThat(response.status()).isEqualTo(200);
	}

	@Test
	void roomsCanBeCreatedListedAndLookedUp() {
		String token = signUp();
		String first = unique("first");
		String second = unique("second");

		Response created = this.api.post("/room", Map.of("name", first), token);
		assertThat(created.status()).isEqualTo(201);
		assertThat(created.body().get("slug").stringValue()).isEqualTo(first);
		int firstId = created.body().get("roomId").intValue();
		createRoom(token, second);

		Response list = this.api.get("/rooms", token);
		assertThat(list.status()).isEqualTo(200);
		JsonNode rooms = list.body().get("rooms");
		assertThat(rooms.size()).isEqualTo(2);
		assertThat(rooms.get(0).get("slug").stringValue()).as("newest first").isEqualTo(second);
		assertThat(rooms.get(1).get("id").intValue()).isEqualTo(firstId);
		Instant createdAt = Instant.parse(rooms.get(1).get("createdAt").stringValue());
		assertThat(createdAt).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(1));

		Response lookup = this.api.get("/room/" + first, signUp());
		assertThat(lookup.status()).isEqualTo(200);
		assertThat(lookup.body().get("room")).isEqualTo(rooms.get(1));
	}

	@Test
	void roomNamesAreUniqueAndValidated() {
		String token = signUp();
		String name = unique("taken");
		createRoom(token, name);

		Response duplicate = this.api.post("/room", Map.of("name", name), signUp());
		assertThat(duplicate.status()).isEqualTo(409);
		assertThat(duplicate.message()).isEqualTo("A room with this name already exists");

		Response tooShortAndInvalid = this.api.post("/room", Map.of("name", "a!"), token);
		assertThat(tooShortAndInvalid.status()).isEqualTo(400);
		assertThat(tooShortAndInvalid.body().get("errors").get("name").stringValue())
			.isEqualTo("Room name must be at least 3 characters");

		Response invalid = this.api.post("/room", Map.of("name", "no spaces"), token);
		assertThat(invalid.body().get("errors").get("name").stringValue())
			.isEqualTo("Room name may only contain letters, numbers, hyphens and underscores");
	}

	@Test
	void unknownRoomIsNotFound() {
		Response response = this.api.get("/room/" + unique("missing"), signUp());

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.message()).isEqualTo("Room not found");
	}

	@Test
	void chatHistoryValidatesTheRoomId() {
		String token = signUp();

		Response invalid = this.api.get("/chats/abc", token);
		assertThat(invalid.status()).isEqualTo(400);
		assertThat(invalid.message()).isEqualTo("Invalid room id");

		assertThat(this.api.get("/chats/0", token).status()).isEqualTo(400);

		Response empty = this.api.get("/chats/" + createRoom(token, unique("empty")), token);
		assertThat(empty.status()).isEqualTo(200);
		assertThat(empty.body().get("messages").isArray()).isTrue();
		assertThat(empty.body().get("messages").size()).isZero();
	}

	@Test
	void unknownRoutesAreNotFound() {
		Response response = this.api.get("/nope", signUp());

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.message()).isEqualTo("Not found");
	}

	@Test
	void corsAllowsTheConfiguredOriginOnly() {
		Response allowed = this.api.send(preflight("http://localhost:3000"));
		assertThat(allowed.status()).isEqualTo(200);
		assertThat(allowed.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
		assertThat(allowed.header("Access-Control-Allow-Credentials")).isEqualTo("true");

		Response denied = this.api.send(preflight("https://evil.example"));
		assertThat(denied.status()).isEqualTo(403);
	}

	private HttpRequest.Builder preflight(String origin) {
		return this.api.request("/signin", null)
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.header("Origin", origin)
			.header("Access-Control-Request-Method", "POST")
			.header("Access-Control-Request-Headers", "content-type,authorization");
	}

	private static List<String> fieldNames(JsonNode node) {
		return node.propertyNames().stream().toList();
	}

}
