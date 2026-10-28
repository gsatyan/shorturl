package com.schwab.assessment.schwab_url_shortener.analytics;

import java.util.List;
import java.util.Objects;

import com.schwab.assessment.schwab_url_shortener.click.ClickEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * The stream processor of docs/DESIGN.md §6.4, as a plain Kafka consumer inside the app: reads batches
 * from {@code clicks}, enriches them, and inserts each batch into ClickHouse.
 * <p>
 * All instances share one consumer group, so each click is processed by one instance. Offsets are committed only
 * after the insert succeeds; a failed batch is retried with backoff (see {@link AnalyticsConfig}), so a ClickHouse
 * outage delays stats instead of losing clicks, as long as it's shorter than the topic's 24-hour retention.
 */
class ClickProcessor {

	private final ClickEnricher enricher;

	private final ClickHouseClickWriter writer;

	private final Counter processed;

	ClickProcessor(ClickEnricher enricher, ClickHouseClickWriter writer, MeterRegistry meterRegistry) {
		this.enricher = enricher;
		this.writer = writer;
		this.processed = Counter.builder("clicks.processed").register(meterRegistry);
	}

	@KafkaListener(id = "click-processor", topics = "${app.kafka.topics.clicks}",
			groupId = "${app.analytics.consumer-group}", containerFactory = AnalyticsConfig.CONTAINER_FACTORY,
			// A new group starts from the oldest click still in the topic, not only new ones.
			properties = "auto.offset.reset=earliest")
	void onClicks(List<ClickEvent> clicks) {
		// Malformed messages arrive as null (ErrorHandlingDeserializer, application.yaml) and are skipped.
		var enriched = clicks.stream().filter(Objects::nonNull).map(enricher::enrich).toList();
		if (!enriched.isEmpty()) {
			writer.insert(enriched);
			processed.increment(enriched.size());
		}
	}

}
