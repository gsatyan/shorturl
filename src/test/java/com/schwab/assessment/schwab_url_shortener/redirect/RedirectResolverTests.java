package com.schwab.assessment.schwab_url_shortener.redirect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

class RedirectResolverTests {

	private static final LinkSnapshot LINK = new LinkSnapshot("https://www.schwab.com/research", LinkStatus.ACTIVE,
			null);

	private final InMemoryLinkCache sharedCache = new InMemoryLinkCache();

	private final DynamoDbLinkReader reader = mock(DynamoDbLinkReader.class);

	private final RedirectResolver resolver = new RedirectResolver(
			new RedirectProperties(1000, Duration.ofSeconds(60), Duration.ofHours(24), Duration.ofHours(1),
					Duration.ofSeconds(30), "v1"),
			sharedCache, reader, new SimpleMeterRegistry());

	@Test
	void missInBothCachesReadsDynamoDbAndFillsRedis() {
		given(reader.find("aZ3kP9q")).willReturn(LINK);

		assertThat(resolver.resolve("aZ3kP9q")).isEqualTo(LINK);
		assertThat(sharedCache.entries).containsEntry("aZ3kP9q", LINK);
	}

	@Test
	void repeatRequestsAreServedInProcess() {
		given(reader.find("aZ3kP9q")).willReturn(LINK);

		for (int i = 0; i < 100; i++) {
			resolver.resolve("aZ3kP9q");
		}

		verify(reader, times(1)).find("aZ3kP9q");
		assertThat(sharedCache.gets).isEqualTo(1);
	}

	@Test
	void redisHitSkipsDynamoDb() {
		sharedCache.entries.put("aZ3kP9q", LINK);

		assertThat(resolver.resolve("aZ3kP9q")).isEqualTo(LINK);
		verify(reader, never()).find("aZ3kP9q");
	}

	@Test
	void unknownCodeIsCachedSoRepeatsDontReachDynamoDb() {
		given(reader.find("zzzzzzz")).willReturn(LinkSnapshot.NOT_FOUND);

		resolver.resolve("zzzzzzz");
		resolver.resolve("zzzzzzz");

		verify(reader, times(1)).find("zzzzzzz");
		assertThat(sharedCache.entries).containsEntry("zzzzzzz", LinkSnapshot.NOT_FOUND);
	}

	@Test
	void dynamoDbFailureIsNotCachedSoTheNextRequestRetries() {
		given(reader.find("aZ3kP9q")).willThrow(SdkClientException.create("unreachable")).willReturn(LINK);

		assertThatExceptionOfType(SdkClientException.class).isThrownBy(() -> resolver.resolve("aZ3kP9q"));
		assertThat(sharedCache.entries).doesNotContainKey("aZ3kP9q");

		assertThat(resolver.resolve("aZ3kP9q")).isEqualTo(LINK);
	}

	@Test
	void evictLocalForcesTheNextRequestBackToRedis() {
		given(reader.find("aZ3kP9q")).willReturn(LINK);
		resolver.resolve("aZ3kP9q");

		var disabled = new LinkSnapshot(LINK.longUrl(), LinkStatus.DISABLED, null);
		sharedCache.entries.put("aZ3kP9q", disabled);
		resolver.evictLocal("aZ3kP9q");

		assertThat(resolver.resolve("aZ3kP9q")).isEqualTo(disabled);
	}

	private static final class InMemoryLinkCache implements LinkCache {

		final Map<String, LinkSnapshot> entries = new HashMap<>();

		int gets;

		@Override
		public Optional<LinkSnapshot> get(String code) {
			gets++;
			return Optional.ofNullable(entries.get(code));
		}

		@Override
		public void put(String code, LinkSnapshot link) {
			entries.put(code, link);
		}

		@Override
		public void evict(String code) {
			entries.remove(code);
		}

	}

}
