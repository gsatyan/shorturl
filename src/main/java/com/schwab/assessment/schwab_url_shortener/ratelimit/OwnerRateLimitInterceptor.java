package com.schwab.assessment.schwab_url_shortener.ratelimit;

import com.schwab.assessment.schwab_url_shortener.auth.ApiKeyAuthenticationInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-owner limits on {@code /api/**} (docs/DESIGN.md §9), with separate budgets for reads ({@code GET},
 * {@code HEAD}) and writes (everything else), so a dashboard polling stats can't use up an owner's creates. Runs after
 * the API key is authenticated, which sets the owner ID.
 */
class OwnerRateLimitInterceptor implements HandlerInterceptor {

	private final RateLimiter rateLimiter;

	private final RateLimitProperties properties;

	private final MeterRegistry meterRegistry;

	OwnerRateLimitInterceptor(RateLimiter rateLimiter, RateLimitProperties properties, MeterRegistry meterRegistry) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		var ownerId = (String) request.getAttribute(ApiKeyAuthenticationInterceptor.OWNER_ID);
		if (ownerId == null) {
			// Not authenticated: the API-key check rejects the request, and the per-IP limit has already counted it.
			return true;
		}
		var read = HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
		var scope = read ? "read" : "write";
		var decision = rateLimiter.tryAcquire("owner:" + ownerId + ":" + scope,
				read ? properties.ownerReads() : properties.ownerWrites(), properties.window());
		if (!decision.allowed()) {
			meterRegistry.counter("ratelimit.rejected", "scope", scope).increment();
			throw new TooManyRequestsException("Too many " + scope + " requests for this API key",
					decision.retryAfter());
		}
		return true;
	}

}
