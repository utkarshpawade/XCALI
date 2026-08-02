package com.drawapp.backend.realtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.drawapp.backend.auth.JwtService;
import com.drawapp.backend.chat.ChatService;
import com.drawapp.backend.realtime.ClientMessage.JoinRoom;
import com.drawapp.backend.realtime.ClientMessage.LeaveRoom;
import com.drawapp.backend.realtime.ClientMessage.Post;
import com.drawapp.backend.room.RoomService;

/**
 * The live half of the app: clients join rooms, and every shape posted to a
 * room is saved and then relayed to the other members.
 * <p>
 * Protocol (JSON text frames):
 * <ul>
 * <li>{@code join_room} &rarr; {@code joined_room}</li>
 * <li>{@code leave_room}</li>
 * <li>{@code chat} &rarr; {@code chat} to every other member</li>
 * <li>problems &rarr; {@code {"type": "error", "message": ...}}</li>
 * </ul>
 */
@Component
public class CanvasWebSocketHandler extends AbstractWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(CanvasWebSocketHandler.class);

	/**
	 * Largest frame accepted. Room for a maximum-size message after JSON
	 * escaping, while bounding what one client can make the server buffer.
	 */
	static final int MAX_FRAME_CHARS = 1_000_000;

	private final JwtService jwtService;

	private final ChatService chatService;

	private final ConnectionRegistry registry;

	private final JsonMapper json;

	public CanvasWebSocketHandler(JwtService jwtService, ChatService chatService, ConnectionRegistry registry,
			JsonMapper json) {
		this.jwtService = jwtService;
		this.chatService = chatService;
		this.registry = registry;
		this.json = json;
	}

	record Joined(String type, String roomId) {
		Joined(String roomId) {
			this("joined_room", roomId);
		}
	}

	record Relayed(String type, String message, String roomId, String userId) {
		Relayed(String message, String roomId, String userId) {
			this("chat", message, roomId, userId);
		}
	}

	record ErrorFrame(String type, String message) {
		ErrorFrame(String message) {
			this("error", message);
		}
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String token = token(session);
		String userId = (token != null) ? this.jwtService.verify(token).orElse(null) : null;
		if (userId == null) {
			// Accepted and then closed, rather than refused at the handshake:
			// browsers only expose the close code, and clients read 1008 as
			// "sign in again".
			session.close(CloseStatus.POLICY_VIOLATION.withReason("Unauthorized"));
			return;
		}
		this.registry.add(new Connection(session, userId));
	}

	/**
	 * Large messages arrive in fragments instead of through one buffer sized
	 * for the largest message, so an idle socket costs a few KB, not megabytes.
	 */
	@Override
	public boolean supportsPartialMessages() {
		return true;
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		Connection connection = this.registry.get(session.getId());
		if (connection == null) {
			return;
		}
		String payload;
		try {
			payload = connection.append(message.getPayload(), message.isLast(), MAX_FRAME_CHARS);
		}
		catch (Connection.MessageTooLargeException ex) {
			connection.close(CloseStatus.TOO_BIG_TO_PROCESS.withReason("Message too large"));
			return;
		}
		if (payload != null) {
			handle(connection, payload);
		}
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
		Connection connection = this.registry.get(session.getId());
		if (connection != null && message.isLast()) {
			send(connection, new ErrorFrame("Unsupported message"));
		}
	}

	@Override
	protected void handlePongMessage(WebSocketSession session, PongMessage message) {
		Connection connection = this.registry.get(session.getId());
		if (connection != null) {
			connection.markAlive();
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.debug("Socket {} failed", session.getId(), exception);
		this.registry.remove(session.getId());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		this.registry.remove(session.getId());
	}

	private void handle(Connection connection, String payload) {
		JsonNode node;
		try {
			node = this.json.readTree(payload);
		}
		catch (JacksonException ex) {
			send(connection, new ErrorFrame("Malformed JSON"));
			return;
		}

		switch (ClientMessage.from(node)) {
			case null -> send(connection, new ErrorFrame("Unsupported message"));
			case JoinRoom join -> {
				this.registry.join(connection, join.roomId());
				send(connection, new Joined(join.roomId()));
			}
			case LeaveRoom leave -> this.registry.leave(connection, leave.roomId());
			case Post post -> post(connection, post);
		}
	}

	private void post(Connection connection, Post post) {
		// Only members of the room may post to it.
		if (!connection.rooms().contains(post.roomId())) {
			send(connection, new ErrorFrame("Join the room before sending"));
			return;
		}
		Integer roomId = RoomService.parseId(post.roomId());
		if (roomId == null) {
			send(connection, new ErrorFrame("Invalid room id"));
			return;
		}

		try {
			this.chatService.post(roomId, connection.userId(), post.message());
		}
		catch (RuntimeException ex) {
			log.error("Failed to persist chat for room {}", roomId, ex);
			send(connection, new ErrorFrame("Could not save your drawing"));
			return;
		}

		// The sender already drew this locally, so echoing it back would
		// duplicate the shape on their canvas.
		TextMessage relayed = text(new Relayed(post.message(), post.roomId(), connection.userId()));
		this.registry.broadcast(post.roomId(), connection, relayed);
	}

	private void send(Connection connection, Object payload) {
		connection.send(text(payload));
	}

	private TextMessage text(Object payload) {
		return new TextMessage(this.json.writeValueAsString(payload));
	}

	/**
	 * Browsers cannot set headers on a WebSocket, so the token normally comes
	 * as {@code ?token=}; other clients may send {@code Authorization: Bearer}.
	 */
	private static @Nullable String token(WebSocketSession session) {
		String fromHeader = JwtService
			.tokenFromAuthorizationHeader(session.getHandshakeHeaders().getFirst(HttpHeaders.AUTHORIZATION));
		if (fromHeader != null) {
			return fromHeader;
		}
		URI uri = session.getUri();
		if (uri == null) {
			return null;
		}
		String raw = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("token");
		return (raw == null || raw.isBlank()) ? null : UriUtils.decode(raw, StandardCharsets.UTF_8);
	}

}
