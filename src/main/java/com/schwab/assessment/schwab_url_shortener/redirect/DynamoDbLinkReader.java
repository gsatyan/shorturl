package com.schwab.assessment.schwab_url_shortener.redirect;

import java.time.Instant;
import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * AP1, the redirect lookup on a cache miss (docs/DESIGN.md §7.2): one eventually consistent {@code GetItem} that
 * reads only the three attributes a redirect needs. Eventually consistent reads cost half as much and are served by
 * the region's own replica.
 */
@Component
class DynamoDbLinkReader {

	private final DynamoDbClient dynamoDb;

	private final String table;

	DynamoDbLinkReader(DynamoDbClient dynamoDb, DynamoDbProperties properties) {
		this.dynamoDb = dynamoDb;
		this.table = properties.table();
	}

	/**
	 * @return the link, or {@link LinkSnapshot#NOT_FOUND}
	 * @throws software.amazon.awssdk.core.exception.SdkException if DynamoDB can't be reached after the SDK's retries
	 */
	LinkSnapshot find(String code) {
		var item = dynamoDb.getItem(request -> request.tableName(table)
			.key(Map.of("pk", AttributeValue.fromS("LINK#" + code), "sk", AttributeValue.fromS("LINK")))
			.projectionExpression("longUrl, #status, expiresAt")
			.expressionAttributeNames(Map.of("#status", "status"))   // "status" is a DynamoDB reserved word
			.consistentRead(false))
			.item();
		if (item == null || item.isEmpty()) {
			return LinkSnapshot.NOT_FOUND;
		}
		return new LinkSnapshot(string(item, "longUrl"), LinkStatus.valueOf(string(item, "status")),
				instant(item, "expiresAt"));
	}

	private static String string(Map<String, AttributeValue> item, String name) {
		var value = item.get(name);
		return (value == null) ? null : value.s();
	}

	private static Instant instant(Map<String, AttributeValue> item, String name) {
		var value = string(item, name);
		return (value == null) ? null : Instant.parse(value);
	}

}
