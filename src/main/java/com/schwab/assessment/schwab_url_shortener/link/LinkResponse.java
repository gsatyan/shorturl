package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Instant;

import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;

/**
 * A link as returned by the API (docs/DESIGN.md §4).
 */
public record LinkResponse(String code, String shortUrl, String longUrl, Instant createdAt, Instant expiresAt,
		LinkStatus status) {
}
