package com.drawapp.backend.auth;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from its bearer token. The principal is the user id,
 * so controllers take it as {@code @AuthenticationPrincipal String userId}.
 * <p>
 * A bad token does not fail the request here: public routes such as /signin
 * must keep working when the browser still holds a stale token. Protected
 * routes reject it through {@link JsonAuthenticationEntryPoint}.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	static final String INVALID_TOKEN_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".invalidToken";

	private final JwtService jwtService;

	private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder
		.getContextHolderStrategy();

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = JwtService.tokenFromAuthorizationHeader(request.getHeader(HttpHeaders.AUTHORIZATION));
		if (token != null) {
			Optional<String> userId = this.jwtService.verify(token);
			if (userId.isPresent()) {
				SecurityContext context = this.securityContextHolderStrategy.createEmptyContext();
				context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId.get(), null,
						AuthorityUtils.NO_AUTHORITIES));
				this.securityContextHolderStrategy.setContext(context);
			}
			else {
				request.setAttribute(INVALID_TOKEN_ATTRIBUTE, Boolean.TRUE);
			}
		}
		chain.doFilter(request, response);
	}

}
