package com.schwab.assessment.schwab_url_shortener.domain;

/**
 * What happened to a link, as published on {@code link-events} (docs/DESIGN.md §6.3). Every type invalidates
 * caches, {@code CREATED} included: the code may have been cached as "not found" before it existed.
 */
public enum LinkEventType {

	CREATED, UPDATED, DISABLED, BLOCKED, DELETED

}
