package com.drawapp.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON over real HTTP, the way the frontends call the API. */
final class ApiClient {

	static final JsonMapper json = JsonMapper.builder().build();

	private static final HttpClient http = HttpClient.newHttpClient();

	private final int port;

	ApiClient(int port) {
		this.port = port;
	}

	record Response(int status, JsonNode body, HttpResponse<String> raw) {

		@Nullable String header(String name) {
			return this.raw.headers().firstValue(name).orElse(null);
		}

		String message() {
			return this.body.get("message").stringValue();
		}

	}

	Response get(String path, @Nullable String token) {
		return send(request(path, token).GET());
	}

	/** Sends {@code body} as JSON, or verbatim when it is already a String. */
	Response post(String path, Object body, @Nullable String token) {
		String content = (body instanceof String raw) ? raw : json.writeValueAsString(body);
		return send(request(path, token).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(content)));
	}

	HttpRequest.Builder request(String path, @Nullable String token) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return builder;
	}

	Response send(HttpRequest.Builder request) {
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
			JsonNode body = isJson ? json.readTree(response.body()) : json.getNodeFactory().stringNode(response.body());
			return new Response(response.statusCode(), body, response);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
