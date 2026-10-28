package com.schwab.assessment.schwab_url_shortener.auth;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import com.schwab.assessment.schwab_url_shortener.domain.Hashing;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * AP11 (docs/DESIGN.md §7.2): looks up {@code APIKEY#<sha-256 of key>} and caches the answer in-process for 60 s,
 * so a busy caller costs one read per minute per instance. A revoked key therefore stops working within 60 s.
 */
@Component
class ApiKeyOwnerResolver implements OwnerResolver {

	private static final Duration CACHE_TTL = Duration.ofSeconds(60);

	private final DynamoDbClient dynamoDb;

	private final String table;

	/** Key hash → owner ID, or empty for an unknown or revoked key. */
	private final Cache<String, Optional<String>> owners = Caffeine.newBuilder()
		.maximumSize(10_000)
		.expireAfterWrite(CACHE_TTL)
		.build();

	ApiKeyOwnerResolver(DynamoDbClient dynamoDb, DynamoDbProperties properties) {
		this.dynamoDb = dynamoDb;
		this.table = properties.table();
	}

	@Override
	public String resolveOwnerId(String credential) {
		return owners.get(Hashing.sha256Hex(credential), this::load).orElseThrow(UnauthorizedException::new);
	}

	private Optional<String> load(String keyHash) {
		var item = dynamoDb.getItem(request -> request.tableName(table)
			.key(Map.of("pk", AttributeValue.fromS("APIKEY#" + keyHash), "sk", AttributeValue.fromS("APIKEY")))
			.projectionExpression("ownerId, revokedAt"))
			.item();
		if (item == null || item.isEmpty() || item.containsKey("revokedAt")) {
			return Optional.empty();
		}
		return Optional.of(item.get("ownerId").s());
	}

}
