package com.schwab.assessment.schwab_url_shortener.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates every {@code /api/**} request before its body is read, and stores the caller's owner ID in the
 * {@value #OWNER_ID} request attribute for controllers ({@code @RequestAttribute(OWNER_ID)}).
 */
@Component
public class ApiKeyAuthenticationInterceptor implements HandlerInterceptor {

	public static final String OWNER_ID = "ownerId";

	private static final String BEARER = "Bearer ";

	private final OwnerResolver ownerResolver;

	ApiKeyAuthenticationInterceptor(OwnerResolver ownerResolver) {
		this.ownerResolver = ownerResolver;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
				|| authorization.substring(BEARER.length()).isBlank()) {
			throw new UnauthorizedException();
		}
		request.setAttribute(OWNER_ID, ownerResolver.resolveOwnerId(authorization.substring(BEARER.length()).strip()));
		return true;
	}

}
