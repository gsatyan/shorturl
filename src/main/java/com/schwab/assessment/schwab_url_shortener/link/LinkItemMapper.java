package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import com.schwab.assessment.schwab_url_shortener.domain.Timestamps;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The only code that builds and reads link items (docs/DESIGN.md §7.1, "Rules that keep the data correct"):
 * DynamoDB checks nothing but the key attributes, so the item's shape lives here and is pinned by tests.
 */
final class LinkItemMapper {

	private LinkItemMapper() {
	}

	static Map<String, AttributeValue> key(String code) {
		return Map.of("pk", AttributeValue.fromS("LINK#" + code), "sk", AttributeValue.fromS("LINK"));
	}

	static StoredLink fromItem(Map<String, AttributeValue> item) {
		var expiresAt = item.get("expiresAt");
		var longUrl = item.get("longUrl");   // removed from deleted-owner tombstones (AP19)
		return new StoredLink(item.get("code").s(), (longUrl == null) ? null : longUrl.s(), item.get("ownerId").s(),
				LinkStatus.valueOf(item.get("status").s()), Instant.parse(item.get("createdAt").s()),
				(expiresAt == null) ? null : Instant.parse(expiresAt.s()), Long.parseLong(item.get("version").n()));
	}

	/**
	 * Reads a link from a GSI1 listing (AP9). The index holds only the projected attributes, which are what a listing
	 * shows; {@code ownerId} and {@code version} aren't among them.
	 */
	static LinkResponse fromIndexItem(Map<String, AttributeValue> item, LinksProperties properties) {
		var code = item.get("code").s();
		var expiresAt = item.get("expiresAt");
		var longUrl = item.get("longUrl");
		return new LinkResponse(code, properties.baseUrl().resolve("/" + code).toString(),
				(longUrl == null) ? null : longUrl.s(), Instant.parse(item.get("createdAt").s()),
				(expiresAt == null) ? null : Instant.parse(expiresAt.s()), LinkStatus.valueOf(item.get("status").s()));
	}

	/** The full GSI1 key of a link, rebuilt from its {@code gsi1sk} ({@code LINK#<createdAt>#<code>}). */
	static Map<String, AttributeValue> indexKey(String ownerId, String gsi1sk) {
		var code = gsi1sk.substring(gsi1sk.lastIndexOf('#') + 1);
		return Map.of("gsi1pk", AttributeValue.fromS("OWNER#" + ownerId), "gsi1sk", AttributeValue.fromS(gsi1sk),
				"pk", AttributeValue.fromS("LINK#" + code), "sk", AttributeValue.fromS("LINK"));
	}

	static Map<String, AttributeValue> toItem(NewLink link) {
		var createdAt = Timestamps.format(link.createdAt());
		var item = new HashMap<String, AttributeValue>();
		item.put("pk", AttributeValue.fromS("LINK#" + link.code()));
		item.put("sk", AttributeValue.fromS("LINK"));
		// GSI1: the owner's links, newest first (AP9).
		item.put("gsi1pk", AttributeValue.fromS("OWNER#" + link.ownerId()));
		item.put("gsi1sk", AttributeValue.fromS("LINK#" + createdAt + "#" + link.code()));
		item.put("code", AttributeValue.fromS(link.code()));
		if (link.seq() != null) {
			item.put("seq", AttributeValue.fromN(link.seq().toString()));
		}
		item.put("longUrl", AttributeValue.fromS(link.longUrl()));
		item.put("ownerId", AttributeValue.fromS(link.ownerId()));
		item.put("status", AttributeValue.fromS(LinkStatus.ACTIVE.name()));
		item.put("createdAt", AttributeValue.fromS(createdAt));
		if (link.expiresAt() != null) {
			item.put("expiresAt", AttributeValue.fromS(Timestamps.format(link.expiresAt())));
		}
		item.put("updatedAt", AttributeValue.fromS(createdAt));
		item.put("version", AttributeValue.fromN("1"));
		return item;
	}

}
