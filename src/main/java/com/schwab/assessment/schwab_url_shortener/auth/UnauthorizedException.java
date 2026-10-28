package com.schwab.assessment.schwab_url_shortener.auth;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * {@code 401} with a {@code WWW-Authenticate} challenge. The detail never says whether a key exists or was revoked.
 */
public class UnauthorizedException extends ApiException {

	public UnauthorizedException() {
		super(HttpStatus.UNAUTHORIZED, "A valid API key is required: Authorization: Bearer <key>");
		getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
	}

}
