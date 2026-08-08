package com.drawapp.backend;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The socket protocol the canvas and the chat demo speak. */
class CanvasSocketTests extends IntegrationTest {

	@Test
	void aMissingOrInvalidTokenClosesWith1008() {
		try (SocketClient noToken = SocketClient.connect(this.port, "")) {
			assertThat(noToken.awaitClose()).isEqualTo(1008);
		}
		try (SocketClient badToken = SocketClient.connect(this.port, "?token=nope")) {
			assertThat(badToken.awaitClose()).isEqualTo(1008);
		}
	}

	@Test
	void joiningARoomIsAcknowledged() {
		try (SocketClient client = connect(signUp())) {
			client.send(Map.of("type", "join_room", "roomId", "42"));

			JsonNode reply = client.next();
			assertThat(reply.get("type").stringValue()).isEqualTo("joined_room");
			assertThat(reply.get("roomId").stringValue()).isEqualTo("42");
		}
	}

	@Test
	void aNumericRoomIdIsTreatedAsTheSameRoom() {
		try (SocketClient client = connect(signUp())) {
			client.send(Map.of("type", "join_room", "roomId", 42));

			assertThat(client.next().get("roomId").stringValue()).isEqualTo("42");
		}
	}

	@Test
	void shapesAreSavedAndRelayedToTheOtherMembersOnly() throws Exception {
		String alice = signUp();
		String bob = signUp();
		int roomId = createRoom(alice, unique("board"));
		String shape = "{\"shape\":{\"type\":\"rect\",\"x\":1,\"y\":2,\"width\":3,\"height\":4}}";

		try (SocketClient aliceSocket = connect(alice); SocketClient bobSocket = connect(bob)) {
			join(aliceSocket, roomId);
			join(bobSocket, roomId);

			aliceSocket.send(Map.of("type", "chat", "roomId", String.valueOf(roomId), "message", shape));

			JsonNode relayed = bobSocket.next();
			assertThat(relayed.get("type").stringValue()).isEqualTo("chat");
			assertThat(relayed.get("message").stringValue()).isEqualTo(shape);
			assertThat(relayed.get("roomId").stringValue()).isEqualTo(String.valueOf(roomId));
			assertThat(relayed.get("userId").stringValue())
				.isEqualTo(this.api.get("/me", alice).body().get("user").get("id").stringValue());

			assertThat(aliceSocket.poll(Duration.ofMillis(300))).as("no echo to the sender").isNull();
		}

		JsonNode history = this.api.get("/chats/" + roomId, bob).body().get("messages");
		assertThat(history.size()).isEqualTo(1);
		assertThat(history.get(0).get("message").stringValue()).isEqualTo(shape);
		assertThat(history.get(0).get("roomId").intValue()).isEqualTo(roomId);
	}

	@Test
	void historyIsReturnedInDrawingOrder() {
		String token = signUp();
		int roomId = createRoom(token, unique("ordered"));

		try (SocketClient client = connect(token)) {
			join(client, roomId);
			for (int i = 0; i < 3; i++) {
				client.send(Map.of("type", "chat", "roomId", roomId, "message", "shape-" + i));
			}
			await().atMost(Duration.ofSeconds(5))
				.until(() -> this.api.get("/chats/" + roomId, token).body().get("messages").size() == 3);
		}

		JsonNode history = this.api.get("/chats/" + roomId, token).body().get("messages");
		List<String> messages = new ArrayList<>();
		history.forEach((row) -> messages.add(row.get("message").stringValue()));
		assertThat(messages).containsExactly("shape-0", "shape-1", "shape-2");
	}

	@Test
	void aLargePencilStrokeGoesThroughInOnePiece() {
		String alice = signUp();
		int roomId = createRoom(alice, unique("pencil"));
		StringBuilder points = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			points.append(i == 0 ? "" : ",").append("{\"x\":").append(1000.123 + i).append(",\"y\":").append(-i).append('}');
		}
		String stroke = "{\"shape\":{\"type\":\"pencil\",\"points\":[" + points + "]}}";
		assertThat(stroke.length()).isGreaterThan(100_000);

		try (SocketClient aliceSocket = connect(alice); SocketClient bobSocket = connect(signUp())) {
			join(aliceSocket, roomId);
			join(bobSocket, roomId);

			aliceSocket.send(Map.of("type", "chat", "roomId", roomId, "message", stroke));

			assertThat(bobSocket.next().get("message").stringValue()).isEqualTo(stroke);
		}
	}

	@Test
	void anOversizedFrameClosesTheSocket() {
		try (SocketClient client = connect(signUp())) {
			client.send("x".repeat(1_000_001));

			assertThat(client.awaitClose()).isEqualTo(1009);
		}
	}

	@Test
	void postingRequiresJoiningFirst() {
		try (SocketClient client = connect(signUp())) {
			client.send(Map.of("type", "chat", "roomId", "1", "message", "hi"));

			assertError(client.next(), "Join the room before sending");
		}
	}

	@Test
	void leavingARoomStopsDelivery() throws Exception {
		String token = signUp();
		int roomId = createRoom(token, unique("leave"));

		try (SocketClient sender = connect(token); SocketClient listener = connect(signUp())) {
			join(sender, roomId);
			join(listener, roomId);
			listener.send(Map.of("type", "leave_room", "roomId", roomId));
			// leave_room has no reply; a round trip proves it was processed.
			listener.send(Map.of("type", "join_room", "roomId", "elsewhere"));
			listener.next();

			sender.send(Map.of("type", "chat", "roomId", roomId, "message", "after-leave"));

			assertThat(listener.poll(Duration.ofMillis(300))).isNull();
		}
	}

	@Test
	void badFramesGetAnErrorReply() {
		try (SocketClient client = connect(signUp())) {
			client.send("{not json");
			assertError(client.next(), "Malformed JSON");

			client.send(Map.of("type", "draw", "roomId", "1"));
			assertError(client.next(), "Unsupported message");

			client.send(Map.of("type", "join_room"));
			assertError(client.next(), "Unsupported message");

			client.send(Map.of("type", "chat", "roomId", "1", "message", "x".repeat(200_001)));
			assertError(client.next(), "Unsupported message");

			client.send(Map.of("type", "join_room", "roomId", "abc"));
			client.next();
			client.send(Map.of("type", "chat", "roomId", "abc", "message", "hi"));
			assertError(client.next(), "Invalid room id");
		}
	}

	@Test
	void postingToARoomThatDoesNotExistReportsTheFailure() {
		try (SocketClient client = connect(signUp())) {
			join(client, 999_999);

			client.send(Map.of("type", "chat", "roomId", 999_999, "message", "lost"));

			assertError(client.next(), "Could not save your drawing");
		}
	}

	@Test
	void liveSocketsArePingedAndKept() {
		try (SocketClient client = connect(signUp())) {
			await().atMost(Duration.ofSeconds(5)).until(() -> client.pings.get() >= 3);

			assertThat(client.closeCode).isNotDone();
			join(client, 1);
		}
	}

	private static void join(SocketClient client, int roomId) {
		client.send(Map.of("type", "join_room", "roomId", String.valueOf(roomId)));
		assertThat(client.next().get("type").stringValue()).isEqualTo("joined_room");
	}

	private static void assertError(JsonNode reply, String message) {
		assertThat(reply.get("type").stringValue()).isEqualTo("error");
		assertThat(reply.get("message").stringValue()).isEqualTo(message);
	}

}
