package com.drawapp.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DatabaseUrlTests {

	@Test
	void localDockerUrl() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://postgres:postgres@localhost:5432/excalidraw?schema=public");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/excalidraw?currentSchema=public");
		assertThat(url.username()).isEqualTo("postgres");
		assertThat(url.password()).isEqualTo("postgres");
	}

	@Test
	void neonUrlKeepsTlsAndChannelBinding() {
		DatabaseUrl url = DatabaseUrl
			.parse("postgresql://owner:pw@ep-x-pooler.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

		assertThat(url.jdbcUrl())
			.isEqualTo("jdbc:postgresql://ep-x-pooler.aws.neon.tech/neondb?sslmode=require&channelBinding=require");
	}

	@Test
	void renderUrlWithoutPort() {
		DatabaseUrl url = DatabaseUrl.parse("postgres://excalidraw:secret@dpg-abc123-a/excalidraw");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-abc123-a/excalidraw");
		assertThat(url.username()).isEqualTo("excalidraw");
	}

	@Test
	void percentEncodedCredentialsAreDecoded() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://us%40er:p%2Fa+ss%3A@host:5432/db");

		assertThat(url.username()).isEqualTo("us@er");
		assertThat(url.password()).as("'+' is literal in a URL's userinfo").isEqualTo("p/a+ss:");
	}

	@Test
	void unencodedAtSignInThePassword() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://user:p@ss@host/db");

		assertThat(url.password()).isEqualTo("p@ss");
		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://host/db");
	}

	@Test
	void prismaOnlyParametersAreTranslatedOrDropped() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://u:p@host/db?pgbouncer=true&connection_limit=5&connect_timeout=10");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://host/db?prepareThreshold=0&connectTimeout=10");
	}

	@Test
	void noCredentials() {
		DatabaseUrl url = DatabaseUrl.parse("postgresql://localhost/db");

		assertThat(url.username()).isNull();
		assertThat(url.password()).isNull();
	}

	@Test
	void jdbcUrlPassesThrough() {
		DatabaseUrl url = DatabaseUrl.parse("jdbc:postgresql://host:5432/db?user=a&password=b");

		assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/db?user=a&password=b");
		assertThat(url.username()).isNull();
	}

	@Test
	void otherSchemesAreRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> DatabaseUrl.parse("mysql://u:p@host/db"));
	}

}
