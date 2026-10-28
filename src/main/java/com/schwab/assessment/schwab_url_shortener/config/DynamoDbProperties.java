package com.schwab.assessment.schwab_url_shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the {@code url_shortener} table (docs/DESIGN.md §7.1).
 *
 * @param endpoint endpoint override, set only for DynamoDB Local; blank uses the regional AWS endpoint
 * @param region   AWS region of the table
 * @param table    table name
 */
@ConfigurationProperties("app.dynamodb")
public record DynamoDbProperties(String endpoint, String region, String table) {
}
