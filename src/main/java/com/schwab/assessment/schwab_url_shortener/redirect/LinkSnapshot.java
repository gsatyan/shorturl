package com.schwab.assessment.schwab_url_shortener.redirect;

import java.time.Instant;

import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;

/**
 * What a redirect needs from a link item, and what the caches hold: the projection of AP1 (docs/DESIGN.md §7.2).
 * {@link #NOT_FOUND} is cached too, so codes that don't exist stay off the database.
 *
 * @param longUrl   destination; {@code null} for a deleted link (the tombstone removes it) or an unknown code
 * @param status    {@code null} only for an unknown code
 * @param expiresAt optional; checked on every request, so expiry is exact even for cached entries
 */
public record LinkSnapshot(String longUrl, LinkStatus status, Instant expiresAt) {

	public static final LinkSnapshot NOT_FOUND = new LinkSnapshot(null, null, null);

	public boolean exists() {
		return status != null;
	}

	public boolean isExpiredAt(Instant now) {
		return expiresAt != null && !now.isBefore(expiresAt);
	}

}
