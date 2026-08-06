package com.drawapp.backend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A real Postgres for the test run, started once on first use. Each test
 * setup asks for its own named database so migrations start from empty.
 */
public final class TestDatabase {

	private static EmbeddedPostgres server;

	private static final Set<String> created = new HashSet<>();

	private TestDatabase() {
	}

	/** Points the application at the named database, creating it if needed. */
	public static void register(DynamicPropertyRegistry registry, String name) {
		String url = jdbcUrl(name);
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> "postgres");
		registry.add("spring.datasource.password", () -> "");
	}

	public static synchronized String jdbcUrl(String name) {
		EmbeddedPostgres postgres = server();
		if (created.add(name)) {
			execute(postgres.getPostgresDatabase(), "CREATE DATABASE \"" + name + "\"");
		}
		return postgres.getJdbcUrl("postgres", name);
	}

	public static synchronized DataSource dataSource(String name) {
		jdbcUrl(name);
		return server().getDatabase("postgres", name);
	}

	public static void execute(DataSource dataSource, String sql) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not run: " + sql, ex);
		}
	}

	private static EmbeddedPostgres server() {
		if (server == null) {
			try {
				server = EmbeddedPostgres.builder().start();
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Could not start embedded Postgres", ex);
			}
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					server.close();
				}
				catch (IOException ex) {
					// Shutting down anyway.
				}
			}));
		}
		return server;
	}

}
