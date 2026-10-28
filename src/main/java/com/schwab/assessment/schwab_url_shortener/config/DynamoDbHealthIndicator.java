package com.schwab.assessment.schwab_url_shortener.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * Reports {@code UP} when the table exists and is {@code ACTIVE}. Registered as {@code dynamoDb}, which the
 * readiness group includes, so an instance takes no traffic until it can reach its system of record.
 */
class DynamoDbHealthIndicator implements HealthIndicator {

	private final DynamoDbClient dynamoDbClient;

	private final String table;

	DynamoDbHealthIndicator(DynamoDbClient dynamoDbClient, String table) {
		this.dynamoDbClient = dynamoDbClient;
		this.table = table;
	}

	@Override
	public Health health() {
		var status = dynamoDbClient.describeTable(request -> request.tableName(table)).table().tableStatus();
		var health = (status == TableStatus.ACTIVE) ? Health.up() : Health.down();
		return health.withDetail("table", table).withDetail("status", status.toString()).build();
	}

}
