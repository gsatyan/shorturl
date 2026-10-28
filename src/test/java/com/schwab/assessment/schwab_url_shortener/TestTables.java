package com.schwab.assessment.schwab_url_shortener;

import java.util.HashMap;
import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.domain.Hashing;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;

/**
 * Creates the {@code url_shortener} table in DynamoDB Local for integration tests. Keep in sync with
 * docker/dynamodb/url_shortener.json, the definition compose and production use (docs/DESIGN.md §7.1).
 */
public final class TestTables {

	private TestTables() {
	}

	/** Creates the table unless an earlier test context, sharing the same container, already did. */
	public static void createUrlShortenerTable(DynamoDbClient dynamoDb, String table) {
		try {
			create(dynamoDb, table);
		}
		catch (ResourceInUseException alreadyExists) {
			// created by another test class
		}
	}

	/** Stores an API key for {@code ownerId} the way AP14 would: only its SHA-256 is kept. */
	public static void putApiKey(DynamoDbClient dynamoDb, String table, String key, String ownerId, boolean revoked) {
		var item = new HashMap<>(Map.of(
				"pk", AttributeValue.fromS("APIKEY#" + Hashing.sha256Hex(key)),
				"sk", AttributeValue.fromS("APIKEY"),
				"gsi1pk", AttributeValue.fromS("OWNER#" + ownerId),
				"gsi1sk", AttributeValue.fromS("APIKEY#2026-10-27T00:00:00.000Z"),
				"ownerId", AttributeValue.fromS(ownerId),
				"keyPrefix", AttributeValue.fromS(key.substring(0, 8)),
				"createdAt", AttributeValue.fromS("2026-10-27T00:00:00.000Z")));
		if (revoked) {
			item.put("revokedAt", AttributeValue.fromS("2026-10-27T01:00:00.000Z"));
		}
		dynamoDb.putItem(request -> request.tableName(table).item(item));
	}

	private static void create(DynamoDbClient dynamoDb, String table) {
		dynamoDb.createTable(request -> request.tableName(table)
			.billingMode(BillingMode.PAY_PER_REQUEST)
			.attributeDefinitions(
					definition -> definition.attributeName("pk").attributeType(ScalarAttributeType.S),
					definition -> definition.attributeName("sk").attributeType(ScalarAttributeType.S),
					definition -> definition.attributeName("gsi1pk").attributeType(ScalarAttributeType.S),
					definition -> definition.attributeName("gsi1sk").attributeType(ScalarAttributeType.S))
			.keySchema(key -> key.attributeName("pk").keyType(KeyType.HASH),
					key -> key.attributeName("sk").keyType(KeyType.RANGE))
			.globalSecondaryIndexes(index -> index.indexName("gsi1")
				.keySchema(key -> key.attributeName("gsi1pk").keyType(KeyType.HASH),
						key -> key.attributeName("gsi1sk").keyType(KeyType.RANGE))
				.projection(projection -> projection.projectionType(ProjectionType.INCLUDE)
					.nonKeyAttributes("code", "longUrl", "status", "createdAt", "expiresAt", "keyPrefix", "revokedAt",
							"reason")))
			.streamSpecification(stream -> stream.streamEnabled(true)
				.streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)));
	}

}
