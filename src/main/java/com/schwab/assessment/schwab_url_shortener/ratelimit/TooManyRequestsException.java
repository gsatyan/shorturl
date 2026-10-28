package com.schwab.assessment.schwab_url_shortener.ratelimit;

import java.time.Duration;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * {@code 429} with {@code Retry-After} in whole seconds, rounded up so a client that waits exactly that long is in the
 * next window.
 */
class TooManyRequestsException extends ApiException {

	TooManyRequestsException(String detail, Duration retryAfter) {
		super(HttpStatus.TOO_MANY_REQUESTS, detail);
		var seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
		getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
	}

}
