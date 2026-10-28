package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import com.schwab.assessment.schwab_url_shortener.domain.Timestamps;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The only code that builds and reads idempotency records (docs/DESIGN.md §7.1). DynamoDB TTL deletes them through
 * the {@code ttl} attribute, in epoch seconds, 24 hours after creation.
 */
final class IdempotencyRecordMapper {

	static final Duration RETENTION = Duration.ofHours(24);

	private IdempotencyRecordMapper() {
	}

	static String pk(String ownerId, String idempotencyKeyHash) {
		return "IDEM#" + ownerId + "#" + idempotencyKeyHash;
	}

	static Map<String, AttributeValue> toItem(String pk, String requestHash, LinkResponse response, Instant now) {
		var stored = new HashMap<String, AttributeValue>();
		stored.put("code", AttributeValue.fromS(response.code()));
		stored.put("shortUrl", AttributeValue.fromS(response.shortUrl()));
		stored.put("longUrl", AttributeValue.fromS(response.longUrl()));
		stored.put("createdAt", AttributeValue.fromS(Timestamps.format(response.createdAt())));
		if (response.expiresAt() != null) {
			stored.put("expiresAt", AttributeValue.fromS(Timestamps.format(response.expiresAt())));
		}
		stored.put("status", AttributeValue.fromS(response.status().name()));
		return Map.of(
				"pk", AttributeValue.fromS(pk),
				"sk", AttributeValue.fromS("IDEM"),
				"requestHash", AttributeValue.fromS(requestHash),
				"status", AttributeValue.fromN("201"),
				"response", AttributeValue.fromM(stored),
				"createdAt", AttributeValue.fromS(Timestamps.format(now)),
				"ttl", AttributeValue.fromN(Long.toString(now.plus(RETENTION).getEpochSecond())));
	}

	static String requestHash(Map<String, AttributeValue> item) {
		return item.get("requestHash").s();
	}

	static LinkResponse response(Map<String, AttributeValue> item) {
		var stored = item.get("response").m();
		var expiresAt = stored.get("expiresAt");
		return new LinkResponse(stored.get("code").s(), stored.get("shortUrl").s(), stored.get("longUrl").s(),
				Instant.parse(stored.get("createdAt").s()), (expiresAt == null) ? null : Instant.parse(expiresAt.s()),
				LinkStatus.valueOf(stored.get("status").s()));
	}

}
