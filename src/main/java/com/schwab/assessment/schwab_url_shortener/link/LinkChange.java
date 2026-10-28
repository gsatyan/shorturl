package com.schwab.assessment.schwab_url_shortener.link;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import org.springframework.http.HttpStatus;

/**
 * What a {@code PATCH /api/v1/urls/{code}} asks to change (docs/DESIGN.md §4). Fields left out stay as they
 * are, so the body is read as a map: a missing {@code expiresAt} means "unchanged", while {@code "expiresAt": null}
 * would remove the expiry, which isn't allowed.
 *
 * @param status    {@code ACTIVE} or {@code DISABLED}; {@code null} if unchanged
 * @param expiresAt a new expiry; {@code null} if unchanged
 */
record LinkChange(LinkStatus status, Instant expiresAt) {

	private static final Set<String> FIELDS = Set.of("status", "expiresAt");

	static LinkChange fromBody(Map<String, Object> body) {
		if (body == null || body.isEmpty()) {
			throw badRequest("Send at least one of: status, expiresAt");
		}
		if (body.containsKey("longUrl")) {
			// A destination can't change after creation: it would make phishing easier (§2.3).
			throw badRequest("longUrl can't be changed; create a new link instead");
		}
		for (var field : body.keySet()) {
			if (!FIELDS.contains(field)) {
				throw badRequest("Unknown field '" + field + "'; only status and expiresAt can be changed");
			}
		}
		return new LinkChange(status(body), expiresAt(body));
	}

	static LinkChange disable() {
		return new LinkChange(LinkStatus.DISABLED, null);
	}

	private static LinkStatus status(Map<String, Object> body) {
		if (!body.containsKey("status")) {
			return null;
		}
		var value = body.get("status");
		// BLOCKED is set only by the safety scanner, DELETED only by owner deletion (AP17, AP19).
		if ("ACTIVE".equals(value)) {
			return LinkStatus.ACTIVE;
		}
		if ("DISABLED".equals(value)) {
			return LinkStatus.DISABLED;
		}
		throw badRequest("status must be ACTIVE or DISABLED");
	}

	private static Instant expiresAt(Map<String, Object> body) {
		if (!body.containsKey("expiresAt")) {
			return null;
		}
		if (!(body.get("expiresAt") instanceof String value)) {
			throw badRequest("expiresAt can't be removed; set a future time, or disable the link to stop it now");
		}
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException invalid) {
			throw badRequest("expiresAt must be an ISO-8601 UTC time, e.g. 2027-01-01T00:00:00Z");
		}
	}

	private static ApiException badRequest(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, detail);
	}

}
