package com.schwab.assessment.schwab_url_shortener.redirect;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis tier: {@code link:<namespace>:<code>} → JSON snapshot (docs/DESIGN.md §7.4). Redis errors and
 * timeouts (200 ms, application.yaml) become misses and are counted in {@code link.cache.errors}, so a slow or
 * missing Redis slows redirects down to DynamoDB speed instead of failing them.
 */
@Component
class RedisLinkCache implements LinkCache {

	private static final Logger log = LoggerFactory.getLogger(RedisLinkCache.class);

	private final StringRedisTemplate redis;

	private final JsonMapper jsonMapper;

	private final RedirectProperties properties;

	private final MeterRegistry meterRegistry;

	RedisLinkCache(StringRedisTemplate redis, JsonMapper jsonMapper, RedirectProperties properties,
			MeterRegistry meterRegistry) {
		this.redis = redis;
		this.jsonMapper = jsonMapper;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public Optional<LinkSnapshot> get(String code) {
		try {
			var json = redis.opsForValue().get(key(code));
			return Optional.ofNullable(json).map(value -> jsonMapper.readValue(value, LinkSnapshot.class));
		}
		catch (RuntimeException failure) {
			error("get", failure);
			return Optional.empty();
		}
	}

	@Override
	public void put(String code, LinkSnapshot link) {
		try {
			redis.opsForValue().set(key(code), jsonMapper.writeValueAsString(link), ttl(link));
		}
		catch (RuntimeException failure) {
			error("put", failure);
		}
	}

	@Override
	public void evict(String code) {
		try {
			redis.delete(key(code));
		}
		catch (RuntimeException failure) {
			error("evict", failure);
		}
	}

	private String key(String code) {
		return "link:" + properties.redisNamespace() + ":" + code;
	}

	private Duration ttl(LinkSnapshot link) {
		if (!link.exists()) {
			return properties.notFoundTtl();
		}
		var jitterMillis = ThreadLocalRandom.current().nextLong(properties.redisTtlJitter().toMillis() + 1);
		return properties.redisTtl().plusMillis(jitterMillis);
	}

	private void error(String operation, RuntimeException failure) {
		meterRegistry.counter("link.cache.errors", "operation", operation).increment();
		log.debug("Redis link cache {} failed", operation, failure);
	}

}
