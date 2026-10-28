package com.schwab.assessment.schwab_url_shortener.click;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class RedirectTrackerTests {

	private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148";

	private static final Instant NOW = Instant.parse("2026-10-27T20:15:03.120Z");

	private final List<ClickEvent> published = new ArrayList<>();

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final RedirectTracker tracker = new RedirectTracker(published::add, meterRegistry,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void successfulRedirectPublishesClickEventAndCountsIt() {
		var request = request(IPHONE);
		request.addHeader("Referer", "https://www.linkedin.com/");

		tracker.track("aZ3kP9q", HttpStatus.FOUND, request);

		assertThat(published).singleElement().satisfies(event -> {
			assertThat(event.eventId()).isNotNull();
			assertThat(event.code()).isEqualTo("aZ3kP9q");
			assertThat(event.timestamp()).isEqualTo(NOW);
			assertThat(event.ip()).isEqualTo("73.15.0.1");
			assertThat(event.userAgent()).isEqualTo(IPHONE);
			assertThat(event.referer()).isEqualTo("https://www.linkedin.com/");
		});
		assertThat(count("MOBILE", "302")).isEqualTo(1);
	}

	@Test
	void notFoundAndGoneAreCountedButAreNotClicks() {
		tracker.track("zzzzzzz", HttpStatus.NOT_FOUND, request(IPHONE));
		tracker.track("aZ3kP9q", HttpStatus.GONE, request("curl/8.7.1"));

		assertThat(published).isEmpty();
		assertThat(count("MOBILE", "404")).isEqualTo(1);
		assertThat(count("BOT", "410")).isEqualTo(1);
	}

	@Test
	void headRequestIsCountedButIsNotAClick() {
		var request = request(IPHONE);
		request.setMethod("HEAD");

		tracker.track("aZ3kP9q", HttpStatus.FOUND, request);

		assertThat(published).isEmpty();
		assertThat(count("MOBILE", "302")).isEqualTo(1);
	}

	@Test
	void eachClickGetsItsOwnEventId() {
		tracker.track("aZ3kP9q", HttpStatus.FOUND, request(IPHONE));
		tracker.track("aZ3kP9q", HttpStatus.FOUND, request(IPHONE));

		assertThat(published).extracting(ClickEvent::eventId).doesNotHaveDuplicates();
		assertThat(count("MOBILE", "302")).isEqualTo(2);
	}

	@Test
	void longHeadersAreTruncatedAndMissingOnesAreNull() {
		var request = request("x".repeat(5_000));

		tracker.track("aZ3kP9q", HttpStatus.FOUND, request);

		assertThat(published).singleElement().satisfies(event -> {
			assertThat(event.userAgent()).hasSize(RedirectTracker.MAX_USER_AGENT_LENGTH);
			assertThat(event.referer()).isNull();
		});
	}

	private static MockHttpServletRequest request(String userAgent) {
		var request = new MockHttpServletRequest("GET", "/aZ3kP9q");
		request.setRemoteAddr("73.15.0.1");
		request.addHeader("User-Agent", userAgent);
		return request;
	}

	private double count(String deviceType, String status) {
		var counter = meterRegistry.find("redirects").tags("device_type", deviceType, "status", status).counter();
		return (counter == null) ? 0 : counter.count();
	}

}
