package com.schwab.assessment.schwab_url_shortener.click;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends click events to Kafka asynchronously. The producer batches in its own bounded buffer; if Kafka is down and
 * the buffer fills, {@code send} gives up after {@code max.block.ms} (500 ms, application.yaml) and the event is
 * dropped and counted in {@code clicks.dropped}. A redirect never waits for delivery.
 * <p>
 * Events are keyed by event ID, not by code, so one viral link's clicks spread over every partition instead of
 * overloading one. Per-link grouping happens later, in ClickHouse.
 */
@Component
class KafkaClickPublisher implements ClickPublisher {

	private static final Logger log = LoggerFactory.getLogger(KafkaClickPublisher.class);

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private final String topic;

	private final Counter dropped;

	KafkaClickPublisher(KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meterRegistry,
			@Value("${app.kafka.topics.clicks}") String topic) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
		this.dropped = Counter.builder("clicks.dropped")
			.description("Click events that couldn't be sent to Kafka")
			.register(meterRegistry);
	}

	@Override
	public void publish(ClickEvent event) {
		try {
			kafkaTemplate.send(topic, event.eventId().toString(), event).whenComplete((result, failure) -> {
				if (failure != null) {
					drop(failure);
				}
			});
		}
		catch (RuntimeException failure) {
			drop(failure);
		}
	}

	private void drop(Throwable failure) {
		dropped.increment();
		log.debug("Dropped click event", failure);
	}

}
