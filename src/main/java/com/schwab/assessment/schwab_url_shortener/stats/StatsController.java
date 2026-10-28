package com.schwab.assessment.schwab_url_shortener.stats;

import java.time.Instant;

import com.schwab.assessment.schwab_url_shortener.auth.ApiKeyAuthenticationInterceptor;
import com.schwab.assessment.schwab_url_shortener.stats.StatsQuery.Granularity;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/urls/{code}/stats?from=…&to=…&granularity=DAY|HOUR&includeBots=false} (docs/DESIGN.md §4).
 * Clicks appear here within about a minute (§2.2, analytics freshness).
 */
@RestController
class StatsController {

	private final StatsService statsService;

	StatsController(StatsService statsService) {
		this.statsService = statsService;
	}

	@GetMapping("/api/v1/urls/{code}/stats")
	ResponseEntity<LinkStats> stats(@RequestAttribute(ApiKeyAuthenticationInterceptor.OWNER_ID) String ownerId,
			@PathVariable String code, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, @RequestParam(defaultValue = "DAY") Granularity granularity,
			@RequestParam(defaultValue = "false") boolean includeBots) {
		var stats = statsService.stats(ownerId, code, from, to, granularity, includeBots);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stats);
	}

}
