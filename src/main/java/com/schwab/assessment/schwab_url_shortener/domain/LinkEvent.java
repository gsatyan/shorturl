package com.schwab.assessment.schwab_url_shortener.domain;

import java.time.Instant;

/**
 * A committed change to a link, published by the stream relay to {@code link-events}, keyed by code so each link's
 * events stay in order (docs/DESIGN.md §6.3). Delivery is at least once: consumers must be idempotent.
 *
 * @param eventId    the DynamoDB stream record's event ID, so a replayed record carries the same ID
 * @param type       what changed
 * @param code       the link's short code
 * @param occurredAt when DynamoDB committed the change (approximate, to the second)
 */
public record LinkEvent(String eventId, LinkEventType type, String code, Instant occurredAt) {
}
