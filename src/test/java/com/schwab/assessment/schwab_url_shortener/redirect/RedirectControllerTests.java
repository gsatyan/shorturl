package com.schwab.assessment.schwab_url_shortener.redirect;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.schwab.assessment.schwab_url_shortener.auth.OwnerResolver;
import com.schwab.assessment.schwab_url_shortener.click.RedirectTracker;
import com.schwab.assessment.schwab_url_shortener.domain.LinkStatus;
import com.schwab.assessment.schwab_url_shortener.ratelimit.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.exception.SdkClientException;

@WebMvcTest(RedirectController.class)
@Import(SimpleMeterRegistry.class)   // the /api/** rate-limit interceptors register counters
class RedirectControllerTests {

	private static final String DESTINATION = "https://www.schwab.com/research";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private RedirectResolver resolver;

	@MockitoBean
	private RedirectTracker tracker;

	@MockitoBean   // needed by the /api/** authentication interceptor, which isn't used by redirects
	private OwnerResolver ownerResolver;

	@MockitoBean   // needed by the /api/** rate-limit interceptors, which aren't used by redirects
	private RateLimiter rateLimiter;

	@Test
	void activeLinkRedirectsWith302() throws Exception {
		given(resolver.resolve("aZ3kP9q")).willReturn(new LinkSnapshot(DESTINATION, LinkStatus.ACTIVE, null));

		mvc.perform(get("/aZ3kP9q"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", DESTINATION))
			.andExpect(header().string("Cache-Control", "max-age=0, private"))
			.andExpect(header().string("Referrer-Policy", "unsafe-url"));
		verify(tracker).track(eq("aZ3kP9q"), eq(HttpStatus.FOUND), any());
	}

	@Test
	void linkBeforeItsExpiryRedirects() throws Exception {
		var tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
		given(resolver.resolve("aZ3kP9q")).willReturn(new LinkSnapshot(DESTINATION, LinkStatus.ACTIVE, tomorrow));

		mvc.perform(get("/aZ3kP9q")).andExpect(status().isFound());
	}

	@Test
	void unknownCodeIs404() throws Exception {
		given(resolver.resolve("zzzzzzz")).willReturn(LinkSnapshot.NOT_FOUND);

		mvc.perform(get("/zzzzzzz"))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Location"))
			.andExpect(jsonPath("$.status").value(404));
		verify(tracker).track(eq("zzzzzzz"), eq(HttpStatus.NOT_FOUND), any());
	}

	@Test
	void expiredLinkIs410() throws Exception {
		var yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
		given(resolver.resolve("aZ3kP9q")).willReturn(new LinkSnapshot(DESTINATION, LinkStatus.ACTIVE, yesterday));

		mvc.perform(get("/aZ3kP9q"))
			.andExpect(status().isGone())
			.andExpect(header().doesNotExist("Location"))
			.andExpect(jsonPath("$.detail").value("This short link has expired"));
		verify(tracker).track(eq("aZ3kP9q"), eq(HttpStatus.GONE), any());
	}

	@Test
	void disabledLinkIs410() throws Exception {
		given(resolver.resolve("aZ3kP9q")).willReturn(new LinkSnapshot(DESTINATION, LinkStatus.DISABLED, null));

		mvc.perform(get("/aZ3kP9q")).andExpect(status().isGone()).andExpect(header().doesNotExist("Location"));
	}

	@Test
	void deletedLinkTombstoneIs410() throws Exception {
		given(resolver.resolve("aZ3kP9q")).willReturn(new LinkSnapshot(null, LinkStatus.DELETED, null));

		mvc.perform(get("/aZ3kP9q")).andExpect(status().isGone());
	}

	@Test
	void blockedLinkShowsWarningPageInsteadOfRedirecting() throws Exception {
		given(resolver.resolve("aZ3kP9q")).willReturn(new LinkSnapshot(DESTINATION, LinkStatus.BLOCKED, null));

		mvc.perform(get("/aZ3kP9q"))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("Location"))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(content().contentTypeCompatibleWith("text/html"))
			.andExpect(content().string(containsString("This link has been blocked")))
			.andExpect(content().string(not(containsString(DESTINATION))));
		verify(tracker).track(eq("aZ3kP9q"), eq(HttpStatus.OK), any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "ab", "has.dot", "a%20b", "thisaliasiswaytoolongtobeavalidalias01" })
	void codesThatCantExistAre404WithoutALookup(String code) throws Exception {
		mvc.perform(get("/" + code)).andExpect(status().isNotFound());
		verify(resolver, never()).resolve(anyString());
	}

	@Test
	void lookupFailureIs503WithRetryAfter() throws Exception {
		given(resolver.resolve("aZ3kP9q")).willThrow(SdkClientException.create("DynamoDB unreachable"));

		mvc.perform(get("/aZ3kP9q"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "1"));
		verify(tracker).track(eq("aZ3kP9q"), eq(HttpStatus.SERVICE_UNAVAILABLE), any());
	}

}
