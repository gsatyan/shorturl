package com.schwab.assessment.schwab_url_shortener.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Rate limits around the API-key check (order 0, {@code AuthWebConfig}): per client IP before it, per owner after it.
 * Redirects and actuator aren't limited here; abuse of redirects is the CDN's job (docs/DESIGN.md §9).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitWebConfig implements WebMvcConfigurer {

	/** Before authentication, so unauthenticated requests are counted too. */
	static final int CLIENT_IP_ORDER = -10;

	/** After authentication, which sets the owner ID. */
	static final int OWNER_ORDER = 10;

	private final RateLimiter rateLimiter;

	private final RateLimitProperties properties;

	private final MeterRegistry meterRegistry;

	RateLimitWebConfig(RateLimiter rateLimiter, RateLimitProperties properties, MeterRegistry meterRegistry) {
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ClientIpRateLimitInterceptor(rateLimiter, properties, meterRegistry))
			.addPathPatterns("/api/**")
			.order(CLIENT_IP_ORDER);
		registry.addInterceptor(new OwnerRateLimitInterceptor(rateLimiter, properties, meterRegistry))
			.addPathPatterns("/api/**")
			.order(OWNER_ORDER);
	}

}
