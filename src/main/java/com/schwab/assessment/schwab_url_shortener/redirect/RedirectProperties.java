package com.schwab.assessment.schwab_url_shortener.redirect;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the redirect lookup tiers (docs/DESIGN.md §6.2). Defaults are in application.yaml.
 *
 * @param localCacheSize maximum Caffeine entries per instance
 * @param localCacheTtl  how long an instance keeps a link before asking Redis again
 * @param redisTtl       base lifetime of a Redis entry for an existing link
 * @param redisTtlJitter random extra lifetime, so entries written together don't expire together
 * @param notFoundTtl    lifetime of a "not found" entry, in both tiers
 * @param redisNamespace key prefix version; changing it invalidates every Redis link entry
 */
@ConfigurationProperties("app.redirect")
public record RedirectProperties(int localCacheSize, Duration localCacheTtl, Duration redisTtl,
		Duration redisTtlJitter, Duration notFoundTtl, String redisNamespace) {
}
