package com.schwab.assessment.schwab_url_shortener.ratelimit;

import java.time.Duration;

/**
 * Counts requests per principal in fixed windows (docs/DESIGN.md §9, §12). Limits protect the service but aren't
 * a source of truth, so implementations never throw: if they can't count, they allow the request.
 */
public interface RateLimiter {

	/**
	 * Counts one request for {@code principal} in the current window.
	 * @param principal who is counted, e.g. {@code owner:<id>:write}; never a raw IP address or API key
	 * @param limit     requests allowed per window
	 * @param window    window length
	 */
	Decision tryAcquire(String principal, int limit, Duration window);

	/**
	 * @param allowed    whether the request is within the limit
	 * @param retryAfter time until the window resets; meaningful only when not allowed
	 */
	record Decision(boolean allowed, Duration retryAfter) {

		static final Decision ALLOW = new Decision(true, Duration.ZERO);

	}

}
