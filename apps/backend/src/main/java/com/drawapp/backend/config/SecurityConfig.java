package com.drawapp.backend.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.drawapp.backend.auth.JsonAuthenticationEntryPoint;
import com.drawapp.backend.auth.JwtAuthenticationFilter;
import com.drawapp.backend.auth.JwtService;

/**
 * Stateless bearer-token auth. Everything except the routes listed here needs
 * a valid token.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	/** Cost factor the existing password hashes were created with. */
	private static final int BCRYPT_STRENGTH = 10;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests((requests) -> requests
				// The WebSocket authenticates from ?token= itself, after the
				// upgrade, so it can close with 1008 the way clients expect.
				.requestMatchers("/health", "/signup", "/signin", WebSocketConfig.PATH, "/error")
				.permitAll()
				.anyRequest()
				.authenticated())
			.exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
			.addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		if (properties.allowsAnyOrigin()) {
			// A pattern rather than "*" so the origin is echoed back, which is
			// what browsers require alongside credentials.
			cors.setAllowedOriginPatterns(List.of("*"));
		}
		else {
			cors.setAllowedOrigins(properties.allowedOrigins());
		}
		cors.setAllowedMethods(List.of("GET", "HEAD", "PUT", "PATCH", "POST", "DELETE"));
		cors.setAllowedHeaders(List.of("*"));
		cors.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

}
