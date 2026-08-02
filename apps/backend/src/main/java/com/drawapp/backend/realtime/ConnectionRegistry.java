package com.drawapp.backend.realtime;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

/**
 * Every open socket, and an index of who is in which room.
 * <p>
 * State is in-process, so the backend must run as a single instance: two
 * instances would split a room's members between them.
 */
@Component
public class ConnectionRegistry {

	private final Map<String, Connection> connections = new ConcurrentHashMap<>();

	private final Map<String, Set<Connection>> rooms = new ConcurrentHashMap<>();

	void add(Connection connection) {
		this.connections.put(connection.id(), connection);
	}

	@Nullable Connection get(String sessionId) {
		return this.connections.get(sessionId);
	}

	void remove(String sessionId) {
		Connection connection = this.connections.remove(sessionId);
		if (connection != null) {
			for (String roomId : connection.rooms()) {
				leave(connection, roomId);
			}
		}
	}

	void join(Connection connection, String roomId) {
		connection.rooms().add(roomId);
		// compute() is atomic per key, so this cannot race leave() dropping an
		// emptied room and strand the member in a set nobody broadcasts to.
		this.rooms.compute(roomId, (id, members) -> {
			Set<Connection> joined = (members != null) ? members : ConcurrentHashMap.newKeySet();
			joined.add(connection);
			return joined;
		});
		// The socket may have closed while this join was in flight; remove()
		// could then have missed the room, so undo it here.
		if (!this.connections.containsKey(connection.id())) {
			leave(connection, roomId);
		}
	}

	void leave(Connection connection, String roomId) {
		connection.rooms().remove(roomId);
		this.rooms.computeIfPresent(roomId, (id, members) -> {
			members.remove(connection);
			return members.isEmpty() ? null : members;
		});
	}

	/** Sends to everyone in the room except the sender. */
	void broadcast(String roomId, Connection sender, TextMessage message) {
		Set<Connection> members = this.rooms.get(roomId);
		if (members == null) {
			return;
		}
		for (Connection member : members) {
			if (member != sender) {
				member.send(message);
			}
		}
	}

	public int size() {
		return this.connections.size();
	}

	/** Drops sockets that stopped answering pings, so the registry cannot grow forever. */
	@Scheduled(fixedRateString = "${app.ws.heartbeat-interval:30s}")
	void heartbeat() {
		for (Connection connection : this.connections.values()) {
			if (!connection.ping()) {
				connection.close(CloseStatus.SESSION_NOT_RELIABLE);
				remove(connection.id());
			}
		}
	}

	/** Runs before the web server stops, so clients see a clean 1001. */
	@EventListener(ContextClosedEvent.class)
	void closeAll() {
		for (Connection connection : this.connections.values()) {
			connection.close(CloseStatus.GOING_AWAY.withReason("Server shutting down"));
		}
	}

}
