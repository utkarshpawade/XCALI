package com.drawapp.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * REST API (auth, rooms, shape history) and the WebSocket server that
 * broadcasts and persists shapes, in one process.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
