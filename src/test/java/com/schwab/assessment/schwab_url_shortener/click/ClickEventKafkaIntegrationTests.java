package com.schwab.assessment.schwab_url_shortener.click;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;

import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A redirect tracked through the real Spring context reaches the {@code clicks} topic as JSON, keyed by event ID.
 * <p>
 * The producer may wait longer than the production 500 ms for its first metadata fetch: this test is about delivery, not the drop-on-timeout path
 * covered by {@link KafkaClickPublisherTests}.
 */
@SpringBootTest(properties = "spring.kafka.producer.properties.max.block.ms=10000")
@Import(TestcontainersConfiguration.class)
class ClickEventKafkaIntegrationTests {

	@Autowired
	private RedirectTracker tracker;

	@Autowired
	private KafkaContainer kafka;

	@Test
	void successfulRedirectIsPublishedToClicksTopic() {
		var request = new MockHttpServletRequest("GET", "/aZ3kP9q");
		request.setRemoteAddr("73.15.0.1");
		request.addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148");
		request.addHeader("Referer", "https://www.linkedin.com/");

		tracker.track("clickit1", HttpStatus.FOUND, request);

		var props = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), "click-event-test");
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
			consumer.subscribe(List.of("clicks"));
			// The Kafka container is shared, so other tests' clicks are on the topic too: find this test's event.
			var record = StreamSupport
				.stream(KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(30)).records("clicks").spliterator(), false)
				.filter(candidate -> candidate.value().contains("\"code\":\"clickit1\""))
				.findFirst()
				.orElseThrow();

			assertThat(record.value()).contains("\"code\":\"clickit1\"")
				.contains("\"ip\":\"73.15.0.1\"")
				.contains("\"referer\":\"https://www.linkedin.com/\"")
				.contains("\"timestamp\":\"20");   // ISO-8601 string, not epoch numbers
			assertThat(record.value()).contains("\"eventId\":\"" + record.key() + "\"");
		}
	}

}
