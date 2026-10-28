package com.schwab.assessment.schwab_url_shortener.redirect;

import com.schwab.assessment.schwab_url_shortener.domain.LinkEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Drops a changed link from both cache tiers, so the next redirect reads the new state (docs/DESIGN.md §6.3).
 * <p>
 * Runs in every instance: each instance's consumer group is unique ({@code url-shortener-${HOSTNAME}},
 * application.yaml), so every instance receives every event and clears its own in-process cache. Every instance also
 * deletes the Redis key; deleting it more than once is harmless, so no single owner is needed. If an event is lost,
 * the in-process TTL (60 s) still bounds staleness there.
 */
@Component
class LinkEventsListener {

	private final RedirectResolver resolver;

	private final LinkCache sharedCache;

	private final MeterRegistry meterRegistry;

	LinkEventsListener(RedirectResolver resolver, LinkCache sharedCache, MeterRegistry meterRegistry) {
		this.resolver = resolver;
		this.sharedCache = sharedCache;
		this.meterRegistry = meterRegistry;
	}

	@KafkaListener(topics = "${app.kafka.topics.link-events}")
	void onLinkEvent(LinkEvent event) {
		resolver.evictLocal(event.code());
		sharedCache.evict(event.code());
		meterRegistry.counter("link.events.received", "type", event.type().name()).increment();
	}

}
