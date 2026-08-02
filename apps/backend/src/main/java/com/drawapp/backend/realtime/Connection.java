package com.drawapp.backend.realtime;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** One authenticated socket and the rooms it has joined. */
final class Connection {

	private static final Logger log = LoggerFactory.getLogger(Connection.class);

	/** How long a send may block on a slow client before it is dropped. */
	private static final int SEND_TIME_LIMIT_MS = 10_000;

	/** Messages queued for a slow client before it is dropped. */
	private static final int SEND_BUFFER_LIMIT_BYTES = 2 * 1024 * 1024;

	/**
	 * Broadcasts, replies and heartbeat pings come from different threads, and
	 * a raw session does not allow concurrent sends; the decorator serializes
	 * them.
	 */
	private final WebSocketSession session;

	private final String userId;

	private final Set<String> rooms = ConcurrentHashMap.newKeySet();

	private volatile boolean alive = true;

	/**
	 * Fragments of a large message still arriving. Only the thread delivering
	 * this session's messages touches it, one message at a time.
	 */
	private @Nullable StringBuilder pending;

	Connection(WebSocketSession session, String userId) {
		this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
		this.userId = userId;
	}

	String id() {
		return this.session.getId();
	}

	String userId() {
		return this.userId;
	}

	Set<String> rooms() {
		return this.rooms;
	}

	void send(WebSocketMessage<?> message) {
		if (!this.session.isOpen()) {
			return;
		}
		try {
			this.session.sendMessage(message);
		}
		catch (IOException | RuntimeException ex) {
			// The decorator closes the session itself when a client falls too far
			// behind; afterConnectionClosed then removes it.
			log.debug("Could not send to socket {}", id(), ex);
		}
	}

	void close(CloseStatus status) {
		try {
			this.session.close(status);
		}
		catch (IOException ex) {
			log.debug("Could not close socket {}", id(), ex);
		}
	}

	void markAlive() {
		this.alive = true;
	}

	/**
	 * Pings the client, and reports whether it answered the previous ping.
	 * Browsers answer pings without any application code.
	 */
	boolean ping() {
		if (!this.alive) {
			return false;
		}
		this.alive = false;
		send(new PingMessage());
		return true;
	}

	/**
	 * Adds a fragment of a message.
	 * @return the whole message once its last fragment arrives, otherwise null
	 * @throws MessageTooLargeException if the message grows past {@code maxChars}
	 */
	@Nullable String append(String fragment, boolean last, int maxChars) {
		if (last && this.pending == null) {
			return fragment;
		}
		StringBuilder buffer = (this.pending != null) ? this.pending : new StringBuilder();
		if (buffer.length() + fragment.length() > maxChars) {
			this.pending = null;
			throw new MessageTooLargeException();
		}
		buffer.append(fragment);
		if (!last) {
			this.pending = buffer;
			return null;
		}
		this.pending = null;
		return buffer.toString();
	}

	static final class MessageTooLargeException extends RuntimeException {

		MessageTooLargeException() {
			super("Message too large", null, false, false);
		}

	}

}
