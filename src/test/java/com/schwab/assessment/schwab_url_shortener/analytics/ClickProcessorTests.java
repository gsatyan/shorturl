package com.schwab.assessment.schwab_url_shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.schwab.assessment.schwab_url_shortener.click.ClickEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.backoff.BackOffExecution;

/**
 * The batch contract of docs/DESIGN.md §6.4: a batch is written whole, malformed messages are skipped, and a failed
 * insert is rethrown so the listener container retries the batch instead of committing its offsets. Writing to a real
 * ClickHouse is covered by the stats integration tests.
 */
class ClickProcessorTests {

	private static final Instant NOW = Instant.parse("2026-10-28T09:00:00Z");

	private final ClickHouseClickWriter writer = mock(ClickHouseClickWriter.class);

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final ClickProcessor processor = new ClickProcessor(
			new ClickEnricher(new VisitorHasher("test-secret"), "us-east-1"), writer, meterRegistry);

	@Test
	void insertsTheWholeBatchEnrichedInOneCall() {
		var first = click("aZ3kP9q");
		var second = click("research");

		processor.onClicks(List.of(first, second));

		var batch = captureInsert();
		assertThat(batch).extracting(EnrichedClick::eventId).containsExactly(first.eventId(), second.eventId());
		assertThat(batch).extracting(EnrichedClick::code).containsExactly("aZ3kP9q", "research");
		assertThat(batch).allSatisfy(click -> assertThat(click.visitorHash()).hasSize(16));
		assertThat(processed()).isEqualTo(2);
	}

	@Test
	void malformedMessagesAreSkipped() {
		// ErrorHandlingDeserializer hands a message it couldn't read to the listener as null.
		var good = click("aZ3kP9q");

		processor.onClicks(Arrays.asList(null, good, null));

		assertThat(captureInsert()).extracting(EnrichedClick::eventId).containsExactly(good.eventId());
		assertThat(processed()).isEqualTo(1);
	}

	@Test
	void batchOfOnlyMalformedMessagesWritesNothing() {
		processor.onClicks(Arrays.asList(null, null));

		then(writer).should(never()).insert(any());
		assertThat(processed()).isZero();
	}

	@Test
	void failedInsertIsRethrownSoTheBatchIsRetried() {
		var outage = new DataAccessResourceFailureException("ClickHouse unavailable");
		willThrow(outage).given(writer).insert(any());

		assertThatThrownBy(() -> processor.onClicks(List.of(click("aZ3kP9q")))).isSameAs(outage);
		assertThat(processed()).isZero();
	}

	@Test
	void retryBackOffGrowsToOneMinuteAndNeverGivesUp() {
		var execution = AnalyticsConfig.retryBackOff().start();
		var delays = new ArrayList<Long>();
		for (int i = 0; i < 1_000; i++) {
			delays.add(execution.nextBackOff());
		}

		assertThat(delays.subList(0, 7)).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L);
		assertThat(delays).doesNotContain(BackOffExecution.STOP)
			.allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(60_000L));
	}

	@SuppressWarnings("unchecked")
	private List<EnrichedClick> captureInsert() {
		ArgumentCaptor<List<EnrichedClick>> batch = ArgumentCaptor.forClass(List.class);
		then(writer).should().insert(batch.capture());
		return batch.getValue();
	}

	private double processed() {
		return meterRegistry.get("clicks.processed").counter().count();
	}

	private static ClickEvent click(String code) {
		return new ClickEvent(UUID.randomUUID(), code, NOW, "73.15.0.1",
				"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36",
				"https://www.linkedin.com/feed/");
	}

}
