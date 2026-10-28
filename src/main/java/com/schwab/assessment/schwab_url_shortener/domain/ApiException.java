package com.schwab.assessment.schwab_url_shortener.domain;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An error returned to an API caller as an RFC 9457 {@code application/problem+json} body (docs/DESIGN.md §4).
 */
public class ApiException extends ErrorResponseException {

	public ApiException(HttpStatus status, String detail) {
		super(status, ProblemDetail.forStatusAndDetail(status, detail), null);
	}

}
