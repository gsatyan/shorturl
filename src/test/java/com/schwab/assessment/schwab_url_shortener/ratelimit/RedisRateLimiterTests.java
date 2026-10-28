package com.schwab.assessment.schwab_url_shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Fail open: when Redis can't be reached the request is allowed and the failure counted. Counting against a real
 * Redis is covered by {@link RedisRateLimiterIntegrationTests}.
 */
class RedisRateLimiterTests {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final RedisRateLimiter limiter = new RedisRateLimiter(redis, meterRegistry,
			Clock.fixed(Instant.parse("2026-10-28T09:00:20Z"), ZoneOffset.UTC));

	@Test
	void redisFailureAllowsTheRequestAndIsCounted() {
		willThrow(new RedisConnectionFailureException("Redis down")).given(redis)
			.execute(any(RedisScript.class), anyList(), (Object) any());

		var decision = limiter.tryAcquire("owner:o1:write", 1, Duration.ofMinutes(1));

		assertThat(decision.allowed()).isTrue();
		assertThat(meterRegistry.get("ratelimit.errors").counter().count()).isEqualTo(1);
	}

}
