package com.schwab.assessment.schwab_url_shortener.click;

/**
 * Records clicks for analytics (docs/DESIGN.md §12). Implementations must never block or fail a redirect: an event
 * that can't be delivered is dropped and counted.
 */
public interface ClickPublisher {

	void publish(ClickEvent event);

}
