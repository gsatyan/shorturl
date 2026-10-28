package com.schwab.assessment.schwab_url_shortener.ratelimit;

import com.schwab.assessment.schwab_url_shortener.domain.Hashing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-client-IP limit on {@code /api/**} (docs/DESIGN.md §9). Runs before the API key is checked, so a client
 * guessing keys is throttled like any other. The IP is hashed before it becomes part of a Redis key, and the key
 * expires with its window.
 * <p>
 * {@code getRemoteAddr} is the client's address: behind nginx, {@code SERVER_FORWARD_HEADERS_STRATEGY=native} reads it
 * from {@code X-Forwarded-For}.
 */
class ClientIpRateLimitInterceptor implements HandlerInterceptor {

	private final RateLimiter rateLimiter;

	private final RateLimitProperties properties;

	private final Counter rejected;

	ClientIpRateLimitInterceptor(RateLimiter rateLimiter, RateLimitProperties properties,
			MeterRegistry meterRegistry) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.rejected = meterRegistry.counter("ratelimit.rejected", "scope", "ip");
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		var decision = rateLimiter.tryAcquire("ip:" + Hashing.sha256Hex(request.getRemoteAddr()),
				properties.clientIp(), properties.window());
		if (!decision.allowed()) {
			rejected.increment();
			throw new TooManyRequestsException("Too many requests from this address", decision.retryAfter());
		}
		return true;
	}

}
