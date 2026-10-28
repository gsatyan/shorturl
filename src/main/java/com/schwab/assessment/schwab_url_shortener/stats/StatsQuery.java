package com.schwab.assessment.schwab_url_shortener.stats;

import java.time.Instant;

/**
 * What to aggregate: one link's clicks in {@code [from, to)}.
 *
 * @param includeBots whether bot clicks count; they're stored either way (§6.4)
 */
record StatsQuery(String code, Instant from, Instant to, Granularity granularity, boolean includeBots) {

	enum Granularity {

		DAY, HOUR

	}

}
