package com.schwab.assessment.schwab_url_shortener.click;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka failures must never reach the redirect. Delivery to a real broker is covered by
 * {@link ClickEventKafkaIntegrationTests}.
 */
class KafkaClickPublisherTests {

	private static final ClickEvent EVENT = new ClickEvent(UUID.randomUUID(), "aZ3kP9q", Instant.now(), "73.15.0.1",
			"curl/8.7.1", null);

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final KafkaClickPublisher publisher = new KafkaClickPublisher(kafkaTemplate, meterRegistry, "clicks");

	@Test
	void keysEventsByEventIdNotByCode() {
		given(kafkaTemplate.send(eq("clicks"), eq(EVENT.eventId().toString()), eq(EVENT)))
			.willReturn(new CompletableFuture<>());

		publisher.publish(EVENT);

		assertThat(dropped()).isZero();
	}

	@Test
	void failedDeliveryIsDroppedAndCounted() {
		given(kafkaTemplate.send(anyString(), anyString(), any()))
			.willReturn(CompletableFuture.failedFuture(new TimeoutException("broker unavailable")));

		assertThatNoException().isThrownBy(() -> publisher.publish(EVENT));
		assertThat(dropped()).isEqualTo(1);
	}

	@Test
	void fullBufferIsDroppedAndCounted() {
		// KafkaTemplate throws synchronously when the producer can't get metadata or buffer space within max.block.ms.
		given(kafkaTemplate.send(anyString(), anyString(), any())).willThrow(new TimeoutException("buffer full"));

		assertThatNoException().isThrownBy(() -> publisher.publish(EVENT));
		assertThat(dropped()).isEqualTo(1);
	}

	private double dropped() {
		return meterRegistry.get("clicks.dropped").counter().count();
	}

}
