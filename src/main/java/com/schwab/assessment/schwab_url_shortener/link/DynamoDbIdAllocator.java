package com.schwab.assessment.schwab_url_shortener.link;

import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

/**
 * AP5 (docs/DESIGN.md §6.1, §7.2): leases blocks of IDs from the {@code COUNTER#short_url} item with an atomic
 * {@code ADD}. If the returned value is {@code n}, this instance owns {@code (n − blockSize, n]}, and allocating an ID
 * is then a local increment with no database call. A restarted instance wastes the rest of its block, which doesn't
 * matter with 3.5 trillion codes.
 */
@Component
class DynamoDbIdAllocator implements IdAllocator {

	private final DynamoDbClient dynamoDb;

	private final String table;

	private final int blockSize;

	private long next;

	private long blockEnd = -1;   // forces a lease on first use

	DynamoDbIdAllocator(DynamoDbClient dynamoDb, DynamoDbProperties dynamoDbProperties,
			LinksProperties linksProperties) {
		this.dynamoDb = dynamoDb;
		this.table = dynamoDbProperties.table();
		this.blockSize = linksProperties.idBlockSize();
	}

	@Override
	public synchronized long nextId() {
		if (next > blockEnd) {
			blockEnd = leaseBlock();
			next = blockEnd - blockSize + 1;
		}
		return next++;
	}

	private long leaseBlock() {
		var attributes = dynamoDb.updateItem(request -> request.tableName(table)
			.key(Map.of("pk", AttributeValue.fromS("COUNTER#short_url"), "sk", AttributeValue.fromS("COUNTER")))
			.updateExpression("ADD #value :block")
			.expressionAttributeNames(Map.of("#value", "value"))   // "value" is a DynamoDB reserved word
			.expressionAttributeValues(Map.of(":block", AttributeValue.fromN(Integer.toString(blockSize))))
			.returnValues(ReturnValue.UPDATED_NEW))
			.attributes();
		return Long.parseLong(attributes.get("value").n());
	}

}
