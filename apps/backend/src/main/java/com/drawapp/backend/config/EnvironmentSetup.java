package com.drawapp.backend.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.StringUtils;

/**
 * Keeps the environment contract the Node backends had:
 * <ul>
 * <li>a {@code .env} file in the working directory is loaded, below real
 * environment variables (like dotenv, it never overrides one);</li>
 * <li>{@code DATABASE_URL} in libpq / Prisma form configures the datasource.</li>
 * </ul>
 * Registered in {@code META-INF/spring.factories}.
 */
public class EnvironmentSetup implements EnvironmentPostProcessor {

	static final String DOTENV_SOURCE = "dotenv";

	static final String DATABASE_URL_SOURCE = "databaseUrl";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		loadDotenv(environment, Path.of(".env"));
		applyDatabaseUrl(environment);
	}

	static void loadDotenv(ConfigurableEnvironment environment, Path file) {
		if (!Files.isRegularFile(file)) {
			return;
		}
		Map<String, Object> values;
		try {
			values = parseDotenv(Files.readAllLines(file, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read " + file.toAbsolutePath(), ex);
		}
		// A SystemEnvironmentPropertySource, so SPRING_DATASOURCE_URL style
		// names bind to spring.datasource.url just as real variables do.
		addBelowEnvironmentVariables(environment.getPropertySources(),
				new SystemEnvironmentPropertySource(DOTENV_SOURCE, values));
	}

	/**
	 * Sits between the environment and application.yml: an explicit
	 * SPRING_DATASOURCE_URL still wins, and DATABASE_URL beats the local
	 * defaults in application.yml.
	 */
	static void applyDatabaseUrl(ConfigurableEnvironment environment) {
		String url = environment.getProperty("DATABASE_URL");
		if (!StringUtils.hasText(url)) {
			return;
		}
		DatabaseUrl parsed = DatabaseUrl.parse(url);
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("spring.datasource.url", parsed.jdbcUrl());
		if (parsed.username() != null) {
			properties.put("spring.datasource.username", parsed.username());
		}
		if (parsed.password() != null) {
			properties.put("spring.datasource.password", parsed.password());
		}
		addBelowEnvironmentVariables(environment.getPropertySources(),
				new MapPropertySource(DATABASE_URL_SOURCE, properties));
	}

	private static void addBelowEnvironmentVariables(MutablePropertySources sources, PropertySource<?> source) {
		String anchor = sources.contains(DOTENV_SOURCE) ? DOTENV_SOURCE
				: StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
		if (sources.contains(anchor)) {
			sources.addAfter(anchor, source);
		}
		else {
			sources.addLast(source);
		}
	}

	/** Parses KEY=VALUE lines the way dotenv does, including quoted values. */
	static Map<String, Object> parseDotenv(List<String> lines) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.startsWith("export ")) {
				line = line.substring("export ".length()).stripLeading();
			}
			int equals = line.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			values.put(line.substring(0, equals).strip(), unquote(line.substring(equals + 1).strip()));
		}
		return values;
	}

	private static String unquote(String value) {
		if (!value.isEmpty()) {
			char quote = value.charAt(0);
			int close = value.lastIndexOf(quote);
			if ((quote == '"' || quote == '\'') && close > 0) {
				String inner = value.substring(1, close);
				return (quote == '"') ? inner.replace("\\n", "\n") : inner;
			}
		}
		int comment = value.indexOf(" #");
		return (comment >= 0) ? value.substring(0, comment).strip() : value;
	}

}
