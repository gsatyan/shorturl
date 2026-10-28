package com.schwab.assessment.schwab_url_shortener.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Timestamps as stored in DynamoDB: ISO-8601 UTC, always with milliseconds, so they sort correctly as strings in
 * sort keys (docs/DESIGN.md §7.1). {@link Instant#toString()} drops zero fractions, which would break that ordering.
 */
public final class Timestamps {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
		.withZone(ZoneOffset.UTC);

	private Timestamps() {
	}

	public static String format(Instant instant) {
		return FORMAT.format(instant);
	}

}
