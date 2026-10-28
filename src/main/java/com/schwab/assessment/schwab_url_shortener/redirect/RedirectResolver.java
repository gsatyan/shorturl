package com.schwab.assessment.schwab_url_shortener.redirect;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Finds a link through the three tiers of docs/DESIGN.md §6.2: in-process Caffeine → Redis → DynamoDB.
 * <p>
 * The tiers are what let one viral link survive: DynamoDB serves at most ~3,000 reads/s per partition, and a hot
 * link is one item on one partition. Caffeine answers repeat requests without a network hop; when an entry
 * expires, {@code Cache.get(key, loader)} lets only one thread per instance reload it while the others wait for that
 * result.
 */
@Component
@EnableConfigurationProperties(RedirectProperties.class)
public class RedirectResolver {

	private final Cache<String, LinkSnapshot> localCache;

	private final LinkCache sharedCache;

	private final DynamoDbLinkReader reader;

	RedirectResolver(RedirectProperties properties, LinkCache sharedCache, DynamoDbLinkReader reader,
			MeterRegistry meterRegistry) {
		this.sharedCache = sharedCache;
		this.reader = reader;
		this.localCache = Caffeine.newBuilder()
			.maximumSize(properties.localCacheSize())
			.expireAfter(Expiry.creating((String code, LinkSnapshot link) -> localTtl(properties, link)))
			.recordStats()
			.build();
		CaffeineCacheMetrics.monitor(meterRegistry, localCache, "redirect-links");
	}

	/**
	 * @return the link, or {@link LinkSnapshot#NOT_FOUND}
	 * @throws RuntimeException if the link isn't cached and DynamoDB fails; nothing is cached in that case
	 */
	public LinkSnapshot resolve(String code) {
		return localCache.get(code, this::loadShared);
	}

	/** Drops the code from this instance's cache; called when a link-events invalidation arrives (§6.3). */
	public void evictLocal(String code) {
		localCache.invalidate(code);
	}

	private LinkSnapshot loadShared(String code) {
		return sharedCache.get(code).orElseGet(() -> {
			var link = reader.find(code);
			sharedCache.put(code, link);
			return link;
		});
	}

	private static Duration localTtl(RedirectProperties properties, LinkSnapshot link) {
		return link.exists() ? properties.localCacheTtl() : properties.notFoundTtl();
	}

}
