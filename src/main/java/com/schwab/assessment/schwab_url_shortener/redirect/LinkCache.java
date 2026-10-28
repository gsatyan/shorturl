package com.schwab.assessment.schwab_url_shortener.redirect;

import java.util.Optional;

/**
 * The shared redirect cache (docs/DESIGN.md §12). It's an optimisation with a database fallback, so
 * implementations never throw: a failed read is a miss, a failed write is skipped.
 */
public interface LinkCache {

	Optional<LinkSnapshot> get(String code);

	void put(String code, LinkSnapshot link);

	void evict(String code);

}
