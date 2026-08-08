package com.drawapp.backend.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentSetupTests {

	@Test
	void parsesDotenvSyntax() {
		Map<String, Object> values = EnvironmentSetup.parseDotenv(List.of(
				"# comment",
				"",
				"DATABASE_URL=\"postgresql://u:p@host/db?sslmode=require\"",
				"JWT_SECRET='single quoted # not a comment'",
				"export PORT=3001",
				"ALLOWED_ORIGINS=http://localhost:3000 # trailing comment",
				"EMPTY=\"\"",
				"not a pair"));

		assertThat(values).containsExactly(
				Map.entry("DATABASE_URL", "postgresql://u:p@host/db?sslmode=require"),
				Map.entry("JWT_SECRET", "single quoted # not a comment"),
				Map.entry("PORT", "3001"),
				Map.entry("ALLOWED_ORIGINS", "http://localhost:3000"),
				Map.entry("EMPTY", ""));
	}

	@Test
	void dotenvNeverOverridesARealEnvironmentVariable(@TempDir Path dir) throws Exception {
		Path file = dir.resolve(".env");
		Files.writeString(file, "PORT=4000\nJWT_SECRET=from-dotenv\n");
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
					new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
							Map.of("PORT", "5000")));

		EnvironmentSetup.loadDotenv(environment, file);

		assertThat(environment.getProperty("PORT")).isEqualTo("5000");
		assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("from-dotenv");
	}

	@Test
	void databaseUrlConfiguresTheDatasourceAboveApplicationDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.addLast(new MapPropertySource("application.yml",
					Map.of("spring.datasource.url", "jdbc:postgresql://localhost:5432/excalidraw")));
		environment.getPropertySources()
			.addFirst(new MapPropertySource("test", Map.of("DATABASE_URL", "postgresql://u:p%21@db.internal:6543/app")));

		EnvironmentSetup.applyDatabaseUrl(environment);

		assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db.internal:6543/app");
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("u");
		assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("p!");
	}

}
