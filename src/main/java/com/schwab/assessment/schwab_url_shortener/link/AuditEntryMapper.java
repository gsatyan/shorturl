package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.schwab.assessment.schwab_url_shortener.domain.Timestamps;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The only code that builds link audit entries (docs/DESIGN.md §7.1): they share the link's partition, so a link's
 * history is one {@code Query} (AP20), and they're written in the same transaction as the change they record.
 */
final class AuditEntryMapper {

	private AuditEntryMapper() {
	}

	/**
	 * @param action  {@code UPDATED}, {@code DISABLED}, …
	 * @param actor   the owner ID, {@code scanner} or an admin ID
	 * @param changes attribute → {old, new}; an absent old or new value is left out
	 */
	static Map<String, AttributeValue> toItem(String code, Instant at, String action, String actor,
			Map<String, String[]> changes) {
		var changeMap = new HashMap<String, AttributeValue>();
		changes.forEach((attribute, oldAndNew) -> {
			var change = new HashMap<String, AttributeValue>();
			if (oldAndNew[0] != null) {
				change.put("old", AttributeValue.fromS(oldAndNew[0]));
			}
			if (oldAndNew[1] != null) {
				change.put("new", AttributeValue.fromS(oldAndNew[1]));
			}
			changeMap.put(attribute, AttributeValue.fromM(change));
		});
		var timestamp = Timestamps.format(at);
		return Map.of(
				"pk", AttributeValue.fromS("LINK#" + code),
				"sk", AttributeValue.fromS("AUDIT#" + timestamp + "#" + UUID.randomUUID()),
				"action", AttributeValue.fromS(action),
				"actor", AttributeValue.fromS(actor),
				"changes", AttributeValue.fromM(changeMap));
	}

}
