package com.schwab.assessment.schwab_url_shortener.stats;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Stats queries (docs/DESIGN.md §12). Implementations count each click once, even if it was delivered twice.
 */
interface StatsRepository {

	/** @return {totalClicks, uniqueVisitors} */
	long[] totals(StatsQuery query);

	/** @return clicks per bucket start; buckets without clicks are absent */
	Map<Instant, Long> series(StatsQuery query);

	List<LinkStats.Referrer> topReferrers(StatsQuery query, int limit);

	Map<String, Long> byDevice(StatsQuery query);

	Map<String, Long> byCountry(StatsQuery query);

}
