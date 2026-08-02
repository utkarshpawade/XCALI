package com.drawapp.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.drawapp.backend.realtime.CanvasWebSocketHandler;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	/** Same path the production reverse proxy already routes to the socket server. */
	public static final String PATH = "/ws";

	private final CanvasWebSocketHandler handler;

	private final AppProperties properties;

	public WebSocketConfig(CanvasWebSocketHandler handler, AppProperties properties) {
		this.handler = handler;
		this.properties = properties;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		String[] origins = this.properties.allowsAnyOrigin() ? new String[] { "*" }
				: this.properties.allowedOrigins().toArray(String[]::new);
		registry.addHandler(this.handler, PATH).setAllowedOriginPatterns(origins);
	}

}
