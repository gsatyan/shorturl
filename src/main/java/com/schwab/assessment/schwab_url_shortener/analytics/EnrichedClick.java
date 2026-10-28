package com.schwab.assessment.schwab_url_shortener.analytics;

import java.time.Instant;
import java.util.UUID;

/**
 * A click as stored in {@code clicks_raw} (docs/DESIGN.md §7.5). Only derived values: the raw IP address and
 * User-Agent end here (§6.4).
 *
 * @param referrer    referrer host, e.g. {@code linkedin.com}; empty if none
 * @param country     ISO 3166 code; empty until an IP-to-country source is chosen
 * @param visitorHash 16 bytes, see {@link VisitorHasher}
 */
record EnrichedClick(UUID eventId, String code, Instant timestamp, String referrer, String device, String country,
		boolean isBot, byte[] visitorHash, String region) {
}
