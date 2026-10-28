package com.schwab.assessment.schwab_url_shortener.relay;

import java.util.Map;
import java.util.Optional;

import com.schwab.assessment.schwab_url_shortener.domain.LinkEvent;
import com.schwab.assessment.schwab_url_shortener.domain.LinkEventType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;

/**
 * Turns a DynamoDB stream record into a link event (AP21, docs/DESIGN.md §7.2). Only link items ({@code sk = LINK})
 * produce events; audit entries, idempotency records, API keys and the rest of the table are skipped.
 */
final class StreamRecordMapper {

	private static final String LINK_PREFIX = "LINK#";

	private StreamRecordMapper() {
	}

	static Optional<LinkEvent> toLinkEvent(Record record) {
		var change = record.dynamodb();
		var keys = change.keys();
		if (!"LINK".equals(string(keys, "sk")) || string(keys, "pk") == null
				|| !string(keys, "pk").startsWith(LINK_PREFIX)) {
			return Optional.empty();
		}
		var code = string(keys, "pk").substring(LINK_PREFIX.length());
		return Optional.of(new LinkEvent(record.eventID(), type(record), code, change.approximateCreationDateTime()));
	}

	private static LinkEventType type(Record record) {
		if (record.eventName() == OperationType.INSERT) {
			return LinkEventType.CREATED;
		}
		if (record.eventName() == OperationType.REMOVE) {
			// Links are tombstoned, never removed (§6.1); treat a removal like a deletion all the same.
			return LinkEventType.DELETED;
		}
		var oldStatus = string(record.dynamodb().oldImage(), "status");
		var newStatus = string(record.dynamodb().newImage(), "status");
		if (newStatus == null || newStatus.equals(oldStatus)) {
			return LinkEventType.UPDATED;
		}
		return switch (newStatus) {
			case "DISABLED" -> LinkEventType.DISABLED;
			case "BLOCKED" -> LinkEventType.BLOCKED;
			case "DELETED" -> LinkEventType.DELETED;
			default -> LinkEventType.UPDATED;   // e.g. re-enabled
		};
	}

	private static String string(Map<String, AttributeValue> attributes, String name) {
		if (attributes == null) {
			return null;
		}
		var value = attributes.get(name);
		return (value == null) ? null : value.s();
	}

}
