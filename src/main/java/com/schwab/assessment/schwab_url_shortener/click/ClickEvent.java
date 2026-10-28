package com.schwab.assessment.schwab_url_shortener.click;

import java.time.Instant;
import java.util.UUID;

/**
 * One successful redirect, as published to the {@code clicks} topic (docs/DESIGN.md §6.4). Holds only what was in
 * the request: the stream processor derives device, country, bot flag and visitor hash, then drops {@code ip} and
 * {@code userAgent}. The topic keeps raw events for 24 hours.
 *
 * @param eventId   unique per click; ClickHouse deduplicates on it, so redelivery never double-counts
 * @param code      short code that was followed
 * @param timestamp when the redirect was served
 * @param ip        client IP, taken from {@code X-Forwarded-For} when behind the load balancer
 * @param userAgent {@code User-Agent} header, truncated; {@code null} if absent
 * @param referer   {@code Referer} header, truncated; {@code null} if absent
 */
public record ClickEvent(UUID eventId, String code, Instant timestamp, String ip, String userAgent, String referer) {
}
