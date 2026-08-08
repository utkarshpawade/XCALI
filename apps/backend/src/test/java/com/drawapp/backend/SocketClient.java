package com.drawapp.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A WebSocket client that records what it receives, like a browser tab would. */
final class SocketClient implements WebSocket.Listener, AutoCloseable {

	private static final JsonMapper json = JsonMapper.builder().build();

	private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

	private final StringBuilder partial = new StringBuilder();

	final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

	final AtomicInteger pings = new AtomicInteger();

	private WebSocket socket;

	static SocketClient connect(int port, String query) {
		SocketClient client = new SocketClient();
		client.socket = HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.buildAsync(URI.create("ws://localhost:" + port + "/ws" + query), client)
			.join();
		return client;
	}

	void send(Object message) {
		this.socket.sendText((message instanceof String raw) ? raw : json.writeValueAsString(message), true).join();
	}

	/** The next frame, parsed; fails if none arrives within five seconds. */
	JsonNode next() {
		try {
			String message = this.messages.poll(5, TimeUnit.SECONDS);
			if (message == null) {
				throw new AssertionError("No message received");
			}
			return json.readTree(message);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** Null when nothing arrives within the given time. */
	String poll(Duration timeout) throws InterruptedException {
		return this.messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	int awaitClose() {
		return this.closeCode.orTimeout(5, TimeUnit.SECONDS).join();
	}

	@Override
	public void onOpen(WebSocket webSocket) {
		webSocket.request(1);
	}

	@Override
	public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		this.partial.append(data);
		if (last) {
			this.messages.add(this.partial.toString());
			this.partial.setLength(0);
		}
		webSocket.request(1);
		return null;
	}

	/** The JDK client answers with a pong on its own. */
	@Override
	public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
		this.pings.incrementAndGet();
		webSocket.request(1);
		return null;
	}

	@Override
	public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
		this.closeCode.complete(statusCode);
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		this.closeCode.completeExceptionally(error);
	}

	@Override
	public void close() {
		try {
			this.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
		}
		catch (CompletionException ex) {
			// Already closed by the server.
		}
		finally {
			this.socket.abort();
		}
	}

}
