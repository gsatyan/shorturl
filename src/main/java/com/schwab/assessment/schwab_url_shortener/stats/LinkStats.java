package com.schwab.assessment.schwab_url_shortener.stats;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code GET /api/v1/urls/{code}/stats} (docs/DESIGN.md §4).
 *
 * @param series one entry per day or hour in the range, including ones with no clicks
 */
public record LinkStats(String code, long totalClicks, long uniqueVisitors, List<Bucket> series,
		List<Referrer> topReferrers, Map<String, Long> byDeviceType, Map<String, Long> byCountry) {

	/** @param bucket {@code 2026-10-24} for days, {@code 2026-10-24T13:00:00Z} for hours */
	public record Bucket(String bucket, long clicks) {
	}

	public record Referrer(String host, long clicks) {
	}

}
