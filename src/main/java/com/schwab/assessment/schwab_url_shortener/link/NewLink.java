package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Instant;

import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;

/**
 * A link about to be written for the first time.
 *
 * @param seq the allocated ID; {@code null} for a custom alias
 */
record NewLink(String code, Long seq, String longUrl, String ownerId, Instant createdAt, Instant expiresAt) {

	LinkResponse toResponse(LinksProperties properties) {
		var shortUrl = properties.baseUrl().resolve("/" + code).toString();
		return new LinkResponse(code, shortUrl, longUrl, createdAt, expiresAt, LinkStatus.ACTIVE);
	}

}
