package com.drawapp.backend;

import java.lang.management.ManagementFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.drawapp.backend.realtime.ConnectionRegistry;

/** Liveness probe for Render, Docker and the reverse proxy. */
@RestController
public class HealthController {

	private final ConnectionRegistry connections;

	public HealthController(ConnectionRegistry connections) {
		this.connections = connections;
	}

	/** @param uptime seconds since start */
	public record Health(String status, double uptime, int connections) {
	}

	@GetMapping("/health")
	public Health health() {
		double uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
		return new Health("ok", uptime, this.connections.size());
	}

}
