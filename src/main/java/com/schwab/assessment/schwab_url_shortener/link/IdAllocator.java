package com.schwab.assessment.schwab_url_shortener.link;

/**
 * Hands out IDs for generated codes (docs/DESIGN.md §12). IDs are unique across all instances and never reused; gaps
 * are fine.
 */
public interface IdAllocator {

	long nextId();

}
