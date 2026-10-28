package com.schwab.assessment.schwab_url_shortener.relay;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stream relay settings. Defaults are in application.yaml.
 *
 * @param enabled         run the relay in this instance. Exactly one instance per region may enable it
 * @param pollInterval    pause between reads of the stream's open shards
 * @param shardRefresh    how often to look for new shards; DynamoDB splits and rotates them on its own
 */
@ConfigurationProperties("app.relay")
public record RelayProperties(boolean enabled, Duration pollInterval, Duration shardRefresh) {
}
