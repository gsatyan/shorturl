package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Instant;

import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;

/**
 * A link as read back from the table, with the {@code version} used for optimistic locking (AP7).
 */
record StoredLink(String code, String longUrl, String ownerId, LinkStatus status, Instant createdAt,
		Instant expiresAt, long version) {

	LinkResponse toResponse(LinksProperties properties) {
		var shortUrl = properties.baseUrl().resolve("/" + code).toString();
		return new LinkResponse(code, shortUrl, longUrl, createdAt, expiresAt, status);
	}

}
