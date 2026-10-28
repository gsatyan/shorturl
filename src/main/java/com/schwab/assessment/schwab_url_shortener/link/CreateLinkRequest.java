package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Instant;

/**
 * Body of {@code POST /api/v1/urls} (docs/DESIGN.md §4).
 *
 * @param longUrl     destination, {@code http} or {@code https}, at most 2048 characters
 * @param customAlias optional; {@code ^[A-Za-z0-9_-]{3,32}$} and not a reserved path segment
 * @param expiresAt   optional; must be in the future
 */
public record CreateLinkRequest(String longUrl, String customAlias, Instant expiresAt) {
}
