package com.schwab.assessment.schwab_url_shortener.redirect;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Pattern;

import com.schwab.assessment.schwab_url_shortener.click.RedirectTracker;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /{code}} (docs/DESIGN.md §4, §6.2).
 * <p>
 * {@code 302}, not {@code 301}: browsers cache a {@code 301} forever, so later clicks would never reach the service,
 * analytics would undercount, and disabling or expiring the link would stop working for those browsers.
 */
@RestController
class RedirectController {

	private static final Logger log = LoggerFactory.getLogger(RedirectController.class);

	// Generated codes are 7 Base62 characters; custom aliases match ^[A-Za-z0-9_-]{3,32}$ (§6.1). Anything else
	// can't exist, so it's rejected before any cache or database lookup.
	private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{3,32}");

	private static final String BLOCKED_PAGE = """
			<!doctype html>
			<html lang="en">
			<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
			<title>Link blocked</title></head>
			<body style="font-family: system-ui, sans-serif; max-width: 36rem; margin: 4rem auto; padding: 0 1rem">
			<h1>This link has been blocked</h1>
			<p>The destination of this short link was identified as unsafe (for example phishing or malware), so we
			are not sending you there.</p>
			</body>
			</html>
			""";

	private final RedirectResolver resolver;

	private final RedirectTracker tracker;

	private final Clock clock;

	@Autowired
	RedirectController(RedirectResolver resolver, RedirectTracker tracker) {
		this(resolver, tracker, Clock.systemUTC());
	}

	RedirectController(RedirectResolver resolver, RedirectTracker tracker, Clock clock) {
		this.resolver = resolver;
		this.tracker = tracker;
		this.clock = clock;
	}

	@GetMapping("/{code}")
	ResponseEntity<?> redirect(@PathVariable String code, HttpServletRequest request) {
		if (!CODE.matcher(code).matches()) {
			return problem(HttpStatus.NOT_FOUND, "No short link with this code");
		}
		LinkSnapshot link;
		try {
			link = resolver.resolve(code);
		}
		catch (RuntimeException failure) {
			log.warn("Link lookup failed for {}", code, failure);
			tracker.track(code, HttpStatus.SERVICE_UNAVAILABLE, request);
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, "1")
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Try again shortly"));
		}

		var response = respond(link);
		tracker.track(code, HttpStatus.valueOf(response.getStatusCode().value()), request);
		return response;
	}

	private ResponseEntity<?> respond(LinkSnapshot link) {
		if (!link.exists()) {
			return problem(HttpStatus.NOT_FOUND, "No short link with this code");
		}
		return switch (link.status()) {
			case ACTIVE -> link.isExpiredAt(clock.instant()) ? problem(HttpStatus.GONE, "This short link has expired")
					: ResponseEntity.status(HttpStatus.FOUND)
						.location(URI.create(link.longUrl()))
						.cacheControl(CacheControl.maxAge(Duration.ZERO).cachePrivate())
						.header("Referrer-Policy", "unsafe-url")   // destinations see where the click came from
						.build();
			case DISABLED, DELETED -> problem(HttpStatus.GONE, "This short link is no longer available");
			// Shown instead of redirecting (§9). Whether users may continue to the destination is open.
			case BLOCKED -> ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.cacheControl(CacheControl.noStore())
				.header("X-Robots-Tag", "noindex")
				.body(BLOCKED_PAGE);
		};
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
		return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
	}

}
