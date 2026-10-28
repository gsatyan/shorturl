package com.schwab.assessment.schwab_url_shortener.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis: {@code rl:<principal>:<window start, epoch seconds>} (docs/DESIGN.md §7.4). Each
 * request is one round trip running a script that increments the counter and, on the first increment, sets its TTL to
 * the window length, so a counter can never be left without an expiry.
 * <p>
 * Redis errors and timeouts (200 ms, application.yaml) allow the request and are counted in {@code ratelimit.errors}
 * (fail open): a Redis outage leaves the API unthrottled rather than unavailable.
 */
@Component
class RedisRateLimiter implements RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

	private static final RedisScript<Long> INCREMENT = RedisScript.of("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
			    redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			return count""", Long.class);

	private final StringRedisTemplate redis;

	private final Counter errors;

	private final Clock clock;

	@Autowired
	RedisRateLimiter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
		this(redis, meterRegistry, Clock.systemUTC());
	}

	RedisRateLimiter(StringRedisTemplate redis, MeterRegistry meterRegistry, Clock clock) {
		this.redis = redis;
		this.errors = Counter.builder("ratelimit.errors")
			.description("Rate-limit checks that couldn't reach Redis; the request was allowed")
			.register(meterRegistry);
		this.clock = clock;
	}

	@Override
	public Decision tryAcquire(String principal, int limit, Duration window) {
		var nowMillis = clock.millis();
		var windowMillis = window.toMillis();
		var windowStart = nowMillis - Math.floorMod(nowMillis, windowMillis);
		var key = "rl:" + principal + ":" + (windowStart / 1000);
		Long count;
		try {
			count = redis.execute(INCREMENT, List.of(key), Long.toString(windowMillis));
		}
		catch (RuntimeException failure) {
			errors.increment();
			log.debug("Rate limit check failed for {}; allowing the request", principal, failure);
			return Decision.ALLOW;
		}
		if (count == null || count <= limit) {
			return Decision.ALLOW;
		}
		return new Decision(false, Duration.ofMillis(windowStart + windowMillis - nowMillis));
	}

}
