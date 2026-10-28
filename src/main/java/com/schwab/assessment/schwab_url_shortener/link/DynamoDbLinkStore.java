package com.schwab.assessment.schwab_url_shortener.link;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Link reads and writes in the write region (AP2–AP4, AP6–AP8, docs/DESIGN.md §7.2). Creates are conditional on
 * {@code attribute_not_exists(pk)}: uniqueness of codes, aliases and idempotency keys comes from these conditions,
 * with no read beforehand. Changes are conditional on the owner, the version read beforehand, and a status the owner
 * may still change.
 */
@Component
class DynamoDbLinkStore {

	private static final String NOT_EXISTS = "attribute_not_exists(pk)";

	private final DynamoDbClient dynamoDb;

	private final String table;

	DynamoDbLinkStore(DynamoDbClient dynamoDb, DynamoDbProperties properties) {
		this.dynamoDb = dynamoDb;
		this.table = properties.table();
	}

	/** Strongly consistent: a retry must see a record written a moment ago. */
	Optional<Map<String, AttributeValue>> findIdempotencyRecord(String pk) {
		var item = dynamoDb.getItem(request -> request.tableName(table)
			.key(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS("IDEM")))
			.consistentRead(true))
			.item();
		return (item == null || item.isEmpty()) ? Optional.empty() : Optional.of(item);
	}

	/** AP6: strongly consistent, so a change is always based on the latest committed version. */
	Optional<StoredLink> findLink(String code) {
		var item = dynamoDb.getItem(request -> request.tableName(table)
			.key(LinkItemMapper.key(code))
			.consistentRead(true))
			.item();
		return (item == null || item.isEmpty()) ? Optional.empty() : Optional.of(LinkItemMapper.fromItem(item));
	}

	/**
	 * AP7: applies {@code set} to the link and writes the audit entry in one transaction, only if the link still
	 * belongs to {@code current}'s owner, is still at {@code current}'s version, and isn't {@code BLOCKED} or
	 * {@code DELETED}.
	 * Increments {@code version} and sets {@code updatedAt}.
	 * @throws LinkChangedException if any condition failed; the caller re-reads to find out which
	 */
	void updateLink(StoredLink current, Map<String, AttributeValue> set, Map<String, AttributeValue> auditEntry,
			String updatedAt) {
		var names = new HashMap<String, String>(Map.of("#status", "status", "#version", "version"));
		var values = new HashMap<String, AttributeValue>(Map.of(
				":owner", AttributeValue.fromS(current.ownerId()),
				":expected", AttributeValue.fromN(Long.toString(current.version())),
				":blocked", AttributeValue.fromS("BLOCKED"),
				":deleted", AttributeValue.fromS("DELETED"),
				":one", AttributeValue.fromN("1"),
				":updatedAt", AttributeValue.fromS(updatedAt)));
		var expression = new StringBuilder("SET #version = #version + :one, updatedAt = :updatedAt");
		set.forEach((attribute, value) -> {
			names.put("#" + attribute, attribute);
			values.put(":" + attribute, value);
			expression.append(", #").append(attribute).append(" = :").append(attribute);
		});

		var update = TransactWriteItem.builder()
			.update(Update.builder()
				.tableName(table)
				.key(LinkItemMapper.key(current.code()))
				.updateExpression(expression.toString())
				.conditionExpression("ownerId = :owner AND #version = :expected"
						+ " AND #status <> :blocked AND #status <> :deleted")
				.expressionAttributeNames(names)
				.expressionAttributeValues(values)
				.build())
			.build();
		try {
			dynamoDb.transactWriteItems(request -> request.transactItems(update, put(auditEntry)));
		}
		catch (TransactionCanceledException cancelled) {
			if (failed(cancelled.cancellationReasons(), 0)) {
				throw new LinkChangedException();
			}
			throw cancelled;
		}
	}

	/**
	 * AP9: one {@code Query} on GSI1 in the owner's partition, newest first. GSI1 is updated asynchronously, so a link
	 * created a moment ago may not be listed yet (§7.2, consistency notes).
	 * <p>
	 * Reads one item more than asked: DynamoDB returns a "continue" key whenever a page is exactly full, even when
	 * nothing follows, which would make clients fetch an empty last page. The extra item tells the two cases apart.
	 * @param afterGsi1sk the last {@code gsi1sk} of the previous page, or {@code null} for the first page
	 * @return the page's items and the {@code gsi1sk} to continue after, or {@code null} if this was the last page
	 */
	IndexPage listLinks(String ownerId, int limit, String afterGsi1sk) {
		var response = dynamoDb.query(request -> {
			request.tableName(table)
				.indexName("gsi1")
				.keyConditionExpression("gsi1pk = :owner AND begins_with(gsi1sk, :links)")
				.expressionAttributeValues(Map.of(":owner", AttributeValue.fromS("OWNER#" + ownerId),
						":links", AttributeValue.fromS("LINK#")))
				.scanIndexForward(false)
				.limit(limit + 1);
			if (afterGsi1sk != null) {
				request.exclusiveStartKey(LinkItemMapper.indexKey(ownerId, afterGsi1sk));
			}
		});
		var items = response.items();
		if (items.size() <= limit) {
			return new IndexPage(items, null);
		}
		var page = items.subList(0, limit);
		return new IndexPage(page, page.get(limit - 1).get("gsi1sk").s());
	}

	record IndexPage(List<Map<String, AttributeValue>> items, String lastGsi1sk) {
	}

	/** @throws CodeTakenException if a link with this code already exists */
	void putLink(Map<String, AttributeValue> link) {
		try {
			dynamoDb.putItem(request -> request.tableName(table).item(link).conditionExpression(NOT_EXISTS));
		}
		catch (ConditionalCheckFailedException taken) {
			throw new CodeTakenException();
		}
	}

	/**
	 * Writes the link and its idempotency record atomically, so an idempotency key can never map to two links.
	 * @throws IdempotencyKeyTakenException if a concurrent request with the same key committed first
	 * @throws CodeTakenException if a link with this code already exists
	 */
	void putLinkWithIdempotencyRecord(Map<String, AttributeValue> link, Map<String, AttributeValue> record) {
		try {
			dynamoDb.transactWriteItems(request -> request.transactItems(put(link), put(record)));
		}
		catch (TransactionCanceledException cancelled) {
			var reasons = cancelled.cancellationReasons();
			if (failed(reasons, 1)) {
				throw new IdempotencyKeyTakenException();
			}
			if (failed(reasons, 0)) {
				throw new CodeTakenException();
			}
			throw cancelled;
		}
	}

	private TransactWriteItem put(Map<String, AttributeValue> item) {
		return TransactWriteItem.builder()
			.put(Put.builder().tableName(table).item(item).conditionExpression(NOT_EXISTS).build())
			.build();
	}

	private static boolean failed(List<CancellationReason> reasons, int index) {
		return reasons.size() > index && "ConditionalCheckFailed".equals(reasons.get(index).code());
	}

	static class LinkChangedException extends RuntimeException {

		LinkChangedException() {
			super(null, null, false, false);
		}

	}

	static class CodeTakenException extends RuntimeException {

		CodeTakenException() {
			super(null, null, false, false);
		}

	}

	static class IdempotencyKeyTakenException extends RuntimeException {

		IdempotencyKeyTakenException() {
			super(null, null, false, false);
		}

	}

}
