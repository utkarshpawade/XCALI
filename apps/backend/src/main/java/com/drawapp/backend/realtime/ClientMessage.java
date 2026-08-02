package com.drawapp.backend.realtime;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** A frame the browser sends over the socket. Every one names a room. */
sealed interface ClientMessage {

	/** Pencil strokes are the largest shapes; 5000 points fit comfortably. */
	int MAX_MESSAGE_LENGTH = 200_000;

	String roomId();

	record JoinRoom(String roomId) implements ClientMessage {
	}

	record LeaveRoom(String roomId) implements ClientMessage {
	}

	/** @param message opaque to the server; the drawing app sends {@code {"shape": ...}} */
	record Post(String roomId, String message) implements ClientMessage {
	}

	/**
	 * Accepts what the old zod schema accepted: a {@code type}, a
	 * {@code roomId} given as a string or a number, and for {@code chat} a
	 * string {@code message}. Returns null for anything else.
	 */
	static @Nullable ClientMessage from(JsonNode node) {
		if (!node.isObject()) {
			return null;
		}
		JsonNode type = node.get("type");
		String roomId = roomId(node.get("roomId"));
		if (type == null || !type.isString() || roomId == null) {
			return null;
		}
		return switch (type.stringValue()) {
			case "join_room" -> new JoinRoom(roomId);
			case "leave_room" -> new LeaveRoom(roomId);
			case "chat" -> {
				JsonNode message = node.get("message");
				boolean valid = message != null && message.isString()
						&& message.stringValue().length() <= MAX_MESSAGE_LENGTH;
				yield valid ? new Post(roomId, message.stringValue()) : null;
			}
			default -> null;
		};
	}

	/** Numbers are rendered the way JavaScript's String() would: 5, not 5.0. */
	private static @Nullable String roomId(@Nullable JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isString()) {
			return node.stringValue();
		}
		if (node.isNumber()) {
			return node.decimalValue().stripTrailingZeros().toPlainString();
		}
		return null;
	}

}
