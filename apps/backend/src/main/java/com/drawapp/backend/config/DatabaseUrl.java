package com.drawapp.backend.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates the libpq / Prisma style {@code DATABASE_URL} that every host
 * hands out ({@code postgresql://user:pass@host:5432/db?sslmode=require})
 * into the JDBC URL and credentials the Postgres driver expects, so the same
 * environment variable keeps working across Render, RDS, Neon and Docker.
 *
 * @param jdbcUrl the {@code jdbc:postgresql://} URL, without credentials
 * @param username the user, or {@code null} when the URL has none
 * @param password the password, or {@code null} when the URL has none
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

	/** libpq names that pgjdbc spells differently. */
	private static final Map<String, String> RENAMED = Map.of(
			"schema", "currentSchema",
			"channel_binding", "channelBinding",
			"connect_timeout", "connectTimeout",
			"application_name", "ApplicationName");

	/** Prisma-only settings with no pgjdbc meaning. */
	private static final Set<String> DROPPED = Set.of(
			"connection_limit", "pool_timeout", "socket_timeout", "statement_cache_size", "sslaccept", "sslidentity",
			"sslpassword");

	public static DatabaseUrl parse(String url) {
		String trimmed = url.trim();
		if (trimmed.startsWith("jdbc:")) {
			return new DatabaseUrl(trimmed, null, null);
		}

		int schemeEnd = trimmed.indexOf("://");
		String scheme = schemeEnd < 0 ? "" : trimmed.substring(0, schemeEnd);
		if (!scheme.equals("postgres") && !scheme.equals("postgresql")) {
			throw new IllegalArgumentException("DATABASE_URL must start with postgresql:// or jdbc:postgresql://");
		}
		String rest = trimmed.substring(schemeEnd + 3);

		// Split on the last '@' so an unencoded '@' in the password still parses.
		String userInfo = null;
		int at = rest.lastIndexOf('@');
		if (at >= 0) {
			userInfo = rest.substring(0, at);
			rest = rest.substring(at + 1);
		}

		String query = "";
		int questionMark = rest.indexOf('?');
		if (questionMark >= 0) {
			query = rest.substring(questionMark + 1);
			rest = rest.substring(0, questionMark);
		}

		// Host and port pass through untouched: pgjdbc accepts the same
		// host:port, [ipv6]:port and multi-host forms as libpq.
		int slash = rest.indexOf('/');
		String hosts = slash < 0 ? rest : rest.substring(0, slash);
		String database = slash < 0 ? "" : rest.substring(slash + 1);
		if (hosts.isEmpty()) {
			throw new IllegalArgumentException("DATABASE_URL has no host");
		}

		String username = null;
		String password = null;
		if (userInfo != null && !userInfo.isEmpty()) {
			int colon = userInfo.indexOf(':');
			username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
			password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
		}

		StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://").append(hosts).append('/').append(database);
		List<String> parameters = translateParameters(query);
		if (!parameters.isEmpty()) {
			jdbcUrl.append('?').append(String.join("&", parameters));
		}
		return new DatabaseUrl(jdbcUrl.toString(), username, password);
	}

	private static List<String> translateParameters(String query) {
		List<String> parameters = new ArrayList<>();
		for (String pair : query.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int equals = pair.indexOf('=');
			String key = equals < 0 ? pair : pair.substring(0, equals);
			String value = equals < 0 ? "" : pair.substring(equals + 1);

			if (DROPPED.contains(key)) {
				continue;
			}
			if (key.equals("pgbouncer")) {
				// Prisma's pgbouncer=true means "no server-side prepared statements".
				if (value.equals("true")) {
					parameters.add("prepareThreshold=0");
				}
				continue;
			}
			parameters.add(RENAMED.getOrDefault(key, key) + "=" + value);
		}
		return parameters;
	}

	/** Percent-decodes without turning '+' into a space, which URLDecoder would. */
	private static String decode(String value) {
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

}
