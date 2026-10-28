package com.schwab.assessment.schwab_url_shortener.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limits for {@code /api/**} (docs/DESIGN.md §9).
 * @param window      length of each fixed counting window
 * @param ownerWrites creates, updates and deletes per owner per window
 * @param ownerReads  gets, lists and stats per owner per window
 * @param clientIp    requests per client IP per window, counted before the API key is checked
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Duration window, int ownerWrites, int ownerReads, int clientIp) {
}
