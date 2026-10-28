package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import com.schwab.assessment.schwab_url_shortener.link.DynamoDbLinkStore.LinkChangedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The read-then-conditional-write loop of AP7. Item shapes and DynamoDB conditions are covered by
 * {@link LinkUpdateIntegrationTests}.
 */
class LinkServiceUpdateTests {

	private static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");

	private final DynamoDbLinkStore store = mock(DynamoDbLinkStore.class);

	private final LinksProperties properties = new LinksProperties(URI.create("https://sch.wb"), List.of(), List.of(),
			10_000);

	private final LinkService service = new LinkService(new LinkValidator(properties), () -> 1L, store, properties,
			new CursorCodec("test-secret"), new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void raceWithAnotherChangeIsRetriedOnTheNewVersion() {
		given(store.findLink("aZ3kP9q")).willReturn(Optional.of(link(LinkStatus.ACTIVE, 3)),
				Optional.of(link(LinkStatus.ACTIVE, 4)));
		willThrow(new LinkChangedException()).willDoNothing()
			.given(store)
			.updateLink(any(), anyMap(), anyMap(), anyString());

		var result = service.update("owner-1", "aZ3kP9q", LinkChange.disable());

		assertThat(result.status()).isEqualTo(LinkStatus.DISABLED);
		var current = ArgumentCaptor.forClass(StoredLink.class);
		verify(store, times(2)).updateLink(current.capture(), anyMap(), anyMap(), anyString());
		assertThat(current.getAllValues()).extracting(StoredLink::version).containsExactly(3L, 4L);
	}

	@Test
	void secondRaceIsReportedAs409() {
		given(store.findLink("aZ3kP9q")).willReturn(Optional.of(link(LinkStatus.ACTIVE, 3)));
		willThrow(new LinkChangedException()).given(store).updateLink(any(), anyMap(), anyMap(), anyString());

		assertStatus(() -> service.update("owner-1", "aZ3kP9q", LinkChange.disable()), 409);
	}

	@Test
	void linkBlockedDuringTheRaceIs409OnRetry() {
		given(store.findLink("aZ3kP9q")).willReturn(Optional.of(link(LinkStatus.ACTIVE, 3)),
				Optional.of(link(LinkStatus.BLOCKED, 4)));
		willThrow(new LinkChangedException()).given(store).updateLink(any(), anyMap(), anyMap(), anyString());

		assertStatus(() -> service.update("owner-1", "aZ3kP9q", LinkChange.disable()), 409);
		verify(store, times(1)).updateLink(any(), anyMap(), anyMap(), anyString());
	}

	@Test
	void anotherOwnersLinkIs404() {
		given(store.findLink("aZ3kP9q")).willReturn(Optional.of(link(LinkStatus.ACTIVE, 3)));

		assertStatus(() -> service.update("owner-2", "aZ3kP9q", LinkChange.disable()), 404);
		verify(store, never()).updateLink(any(), anyMap(), anyMap(), anyString());
	}

	@Test
	void changeMatchingTheCurrentStateWritesNothing() {
		given(store.findLink("aZ3kP9q")).willReturn(Optional.of(link(LinkStatus.DISABLED, 3)));

		service.disable("owner-1", "aZ3kP9q");

		verify(store, never()).updateLink(any(), anyMap(), anyMap(), anyString());
	}

	@Test
	void pastExpiryIs400WithoutReading() {
		assertStatus(() -> service.update("owner-1", "aZ3kP9q", new LinkChange(null, NOW)), 400);
		verify(store, never()).findLink(eq("aZ3kP9q"));
	}

	private static StoredLink link(LinkStatus status, long version) {
		return new StoredLink("aZ3kP9q", "https://www.schwab.com", "owner-1", status, NOW.minusSeconds(3600), null,
				version);
	}

	private static void assertStatus(Runnable call, int status) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(call::run)
			.satisfies(failure -> assertThat(failure.getStatusCode().value()).isEqualTo(status));
	}

}
