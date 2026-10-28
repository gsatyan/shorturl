package com.schwab.assessment.schwab_url_shortener.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Click processor settings. Defaults are in application.yaml.
 *
 * @param consumerGroup     Kafka consumer group shared by every instance, so they split the {@code clicks} partitions
 * @param visitorSaltSecret secret from which each day's visitor-hash salt is derived
 * @param region            region recorded with each click
 */
@ConfigurationProperties("app.analytics")
public record AnalyticsProperties(String consumerGroup, String visitorSaltSecret, String region) {
}
