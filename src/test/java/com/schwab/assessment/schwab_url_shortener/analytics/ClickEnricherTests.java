package com.schwab.assessment.schwab_url_shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.schwab.assessment.schwab_url_shortener.click.ClickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClickEnricherTests {

	private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148";

	private static final LocalDate DAY = LocalDate.of(2026, 10, 27);

	private final VisitorHasher hasher = new VisitorHasher("test-secret");

	private final ClickEnricher enricher = new ClickEnricher(hasher, "us-east-1");

	@Test
	void derivesStoredFieldsAndKeepsNoRawIpOrUserAgent() {
		var click = new ClickEvent(UUID.randomUUID(), "aZ3kP9q", Instant.parse("2026-10-27T20:15:03.120Z"),
				"73.15.0.1", IPHONE, "https://www.linkedin.com/feed/?trk=secret-token");

		var enriched = enricher.enrich(click);

		assertThat(enriched.eventId()).isEqualTo(click.eventId());
		assertThat(enriched.code()).isEqualTo("aZ3kP9q");
		assertThat(enriched.timestamp()).isEqualTo(click.timestamp());
		assertThat(enriched.referrer()).isEqualTo("linkedin.com");
		assertThat(enriched.device()).isEqualTo("MOBILE");
		assertThat(enriched.isBot()).isFalse();
		assertThat(enriched.country()).isEmpty();
		assertThat(enriched.region()).isEqualTo("us-east-1");
		assertThat(enriched.visitorHash()).hasSize(16);
		assertThat(enriched.toString()).doesNotContain("73.15.0.1").doesNotContain("iPhone").doesNotContain("secret-token");
	}

	@Test
	void botsAreFlagged() {
		var click = new ClickEvent(UUID.randomUUID(), "aZ3kP9q", Instant.now(), "1.2.3.4",
				"Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)", null);

		var enriched = enricher.enrich(click);

		assertThat(enriched.isBot()).isTrue();
		assertThat(enriched.device()).isEqualTo("BOT");
	}

	@Test
	void sameVisitorSameDayGetsTheSameIdButADifferentOneTheNextDay() {
		var today = hasher.hash(DAY, "73.15.0.1", IPHONE);

		assertThat(hasher.hash(DAY, "73.15.0.1", IPHONE)).isEqualTo(today);
		assertThat(hasher.hash(DAY.plusDays(1), "73.15.0.1", IPHONE)).isNotEqualTo(today);
		assertThat(hasher.hash(DAY, "73.15.0.2", IPHONE)).isNotEqualTo(today);
		assertThat(new VisitorHasher("another-secret").hash(DAY, "73.15.0.1", IPHONE)).isNotEqualTo(today);
	}

	@ParameterizedTest
	@CsvSource(nullValues = "NULL", value = { "https://www.linkedin.com/feed/, linkedin.com", "https://t.co/abc, t.co",
			"HTTPS://Mail.Google.com/mail/u/0, mail.google.com", "NULL, ''", "'', ''", "not a url, ''",
			"android-app://com.slack, com.slack" })
	void keepsOnlyTheReferrerHost(String referer, String host) {
		assertThat(ClickEnricher.referrerHost(referer)).isEqualTo(host);
	}

}
