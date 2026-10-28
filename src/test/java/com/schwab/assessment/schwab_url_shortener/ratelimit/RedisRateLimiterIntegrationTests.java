package com.schwab.assessment.schwab_url_shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Fixed-window counting against a real Redis, with a clock the test moves (docs/DESIGN.md §7.4). Each test
 * counts its own principal, so tests sharing the Redis container don't see each other's counters.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisRateLimiterIntegrationTests {

	private static final Duration MINUTE = Duration.ofMinutes(1);

	/** 20 s into a minute window that starts at 09:00:00. */
	private static final Instant START = Instant.parse("2026-10-28T09:00:20Z");

	@Autowired
	private StringRedisTemplate redis;

	private final MutableClock clock = new MutableClock(START);

	@Test
	void allowsUpToTheLimitThenRejectsUntilTheWindowEnds() {
		var limiter = limiter();
		var principal = principal();

		for (int i = 0; i < 3; i++) {
			assertThat(limiter.tryAcquire(principal, 3, MINUTE).allowed()).isTrue();
		}
		var rejected = limiter.tryAcquire(principal, 3, MINUTE);

		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(40));

		clock.set(Instant.parse("2026-10-28T09:00:59.500Z"));
		assertThat(limiter.tryAcquire(principal, 3, MINUTE).retryAfter()).isEqualTo(Duration.ofMillis(500));

		clock.set(Instant.parse("2026-10-28T09:01:00Z"));
		assertThat(limiter.tryAcquire(principal, 3, MINUTE).allowed()).as("new window").isTrue();
	}

	@Test
	void principalsAreCountedSeparately() {
		var limiter = limiter();
		var busy = principal();
		var quiet = principal();
		limiter.tryAcquire(busy, 1, MINUTE);

		assertThat(limiter.tryAcquire(busy, 1, MINUTE).allowed()).isFalse();
		assertThat(limiter.tryAcquire(quiet, 1, MINUTE).allowed()).isTrue();
	}

	@Test
	void counterExpiresWithItsWindow() {
		var principal = principal();
		limiter().tryAcquire(principal, 10, MINUTE);

		var key = "rl:" + principal + ":" + Instant.parse("2026-10-28T09:00:00Z").getEpochSecond();
		assertThat(redis.opsForValue().get(key)).isEqualTo("1");
		assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(MINUTE.toMillis());
	}

	private RedisRateLimiter limiter() {
		return new RedisRateLimiter(redis, new SimpleMeterRegistry(), clock);
	}

	private static String principal() {
		return "test:" + UUID.randomUUID();
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void set(Instant now) {
			this.now = now;
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
