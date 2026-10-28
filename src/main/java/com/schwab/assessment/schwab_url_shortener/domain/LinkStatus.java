package com.schwab.assessment.schwab_url_shortener.domain;

/**
 * Lifecycle of a link (docs/DESIGN.md §7.1). Links are never deleted from the table: {@code DELETED} is a tombstone
 * that keeps the code taken forever.
 */
public enum LinkStatus {

	ACTIVE, DISABLED, BLOCKED, DELETED

}
