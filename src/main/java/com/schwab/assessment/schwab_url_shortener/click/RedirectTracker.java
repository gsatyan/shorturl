package com.schwab.assessment.schwab_url_shortener.click;

import java.time.Clock;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The redirect endpoint's single call after it has chosen a response: counts every outcome in the {@code redirects}
 * metric, and publishes a click event for successful {@code GET} redirects only. {@code HEAD} requests (link
 * preview fetchers, uptime checks) get the same response but aren't clicks.
 * <p>
 * The metric is tagged by device type and HTTP status, never by code: one series per code would mean millions of
 * series in Prometheus. Per-link numbers come from the click events.
 */
@Component
public class RedirectTracker {

	static final int MAX_USER_AGENT_LENGTH = 512;

	static final int MAX_REFERER_LENGTH = 2048;

	private final ClickPublisher clickPublisher;

	private final MeterRegistry meterRegistry;

	private final Clock clock;

	@Autowired
	public RedirectTracker(ClickPublisher clickPublisher, MeterRegistry meterRegistry) {
		this(clickPublisher, meterRegistry, Clock.systemUTC());
	}

	RedirectTracker(ClickPublisher clickPublisher, MeterRegistry meterRegistry, Clock clock) {
		this.clickPublisher = clickPublisher;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
	}

	/**
	 * @param code   the requested short code
	 * @param status the response sent: {@code 302}, {@code 404} or {@code 410}
	 */
	public void track(String code, HttpStatus status, HttpServletRequest request) {
		var userAgent = truncate(request.getHeader(HttpHeaders.USER_AGENT), MAX_USER_AGENT_LENGTH);
		meterRegistry.counter("redirects",
				"device_type", DeviceType.fromUserAgent(userAgent).name(),
				"status", String.valueOf(status.value()))
			.increment();

		if (status == HttpStatus.FOUND && HttpMethod.GET.matches(request.getMethod())) {
			// getRemoteAddr is the client's IP: SERVER_FORWARD_HEADERS_STRATEGY=native reads X-Forwarded-For from nginx.
			clickPublisher.publish(new ClickEvent(UUID.randomUUID(), code, clock.instant(), request.getRemoteAddr(),
					userAgent, truncate(request.getHeader(HttpHeaders.REFERER), MAX_REFERER_LENGTH)));
		}
	}

	private static String truncate(String value, int maxLength) {
		return (value == null || value.length() <= maxLength) ? value : value.substring(0, maxLength);
	}

}
