package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkValidatorTests {

	private static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");

	private final LinkValidator validator = new LinkValidator(new LinksProperties(URI.create("https://sch.wb"),
			List.of("malware.example"), List.of("api", "actuator"), 10_000));

	@ParameterizedTest
	@ValueSource(strings = { "https://www.schwab.com/research", "http://example.com", "HTTPS://Example.com/a?b=c#d",
			"https://example.com:8443/path" })
	void acceptsHttpAndHttpsUrls(String url) {
		assertThatCode(() -> validator.validate(request(url, null, null), NOW)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "javascript:alert(1)", "data:text/html,hi", "file:///etc/passwd",
			"ftp://example.com", "www.schwab.com", "https://", "https://www.schwab.com@evil.example/",
			"https://sch.wb/abc", "https://exa mple.com" })
	void rejectsUnsafeOrMalformedUrlsWith400(String url) {
		assertStatus(request(url, null, null), 400);
	}

	@Test
	void rejectsMissingAndOverlongUrlsWith400() {
		assertStatus(request(null, null, null), 400);
		assertStatus(request("https://example.com/" + "a".repeat(LinkValidator.MAX_URL_LENGTH), null, null), 400);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://malware.example/x", "https://cdn.malware.example/x", "https://MALWARE.example" })
	void blockedHostsAndTheirSubdomainsAre422(String url) {
		assertStatus(request(url, null, null), 422);
	}

	@Test
	void hostThatOnlyEndsLikeABlockedOneIsAllowed() {
		assertThatCode(() -> validator.validate(request("https://notmalware.example", null, null), NOW))
			.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ab", "has space", "dot.ted", "slash/ed", "api", "API", "actuator",
			"thisaliasiswaytoolongtobeavalidalias01" })
	void rejectsInvalidOrReservedAliasesWith400(String alias) {
		assertStatus(request("https://example.com", alias, null), 400);
	}

	@Test
	void acceptsValidAlias() {
		assertThatCode(() -> validator.validate(request("https://example.com", "q3-Research_2026", null), NOW))
			.doesNotThrowAnyException();
	}

	@Test
	void expiryMustBeInTheFuture() {
		assertStatus(request("https://example.com", null, NOW), 400);
		assertStatus(request("https://example.com", null, NOW.minusSeconds(1)), 400);
		assertThatCode(() -> validator.validate(request("https://example.com", null, NOW.plusSeconds(1)), NOW))
			.doesNotThrowAnyException();
	}

	private void assertStatus(CreateLinkRequest request, int status) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> validator.validate(request, NOW))
			.satisfies(failure -> assertThat(failure.getStatusCode().value()).isEqualTo(status));
	}

	private static CreateLinkRequest request(String url, String alias, Instant expiresAt) {
		return new CreateLinkRequest(url, alias, expiresAt);
	}

}
