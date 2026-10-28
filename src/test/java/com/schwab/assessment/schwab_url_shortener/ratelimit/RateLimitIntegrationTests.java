package com.schwab.assessment.schwab_url_shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schwab.assessment.schwab_url_shortener.TestTables;
import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The {@code /api/**} limits of docs/DESIGN.md §9 end to end, with small limits and a one-hour window so a
 * test can't straddle a window boundary. Each test uses its own client IP and API key, so their counters don't mix.
 */
@SpringBootTest(properties = { "app.rate-limit.window=1h", "app.rate-limit.owner-writes=3",
		"app.rate-limit.owner-reads=4", "app.rate-limit.client-ip=10" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RateLimitIntegrationTests {

	private static final String WRITER_KEY = "ratelimit-writer-key";

	private static final String OTHER_WRITER_KEY = "ratelimit-other-writer-key";

	private static final String READER_KEY = "ratelimit-reader-key";

	@Autowired
	private MockMvc mvc;

	@BeforeAll
	static void createTableAndKeys(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
		TestTables.putApiKey(dynamoDb, properties.table(), WRITER_KEY, "ratelimit-writer", false);
		TestTables.putApiKey(dynamoDb, properties.table(), OTHER_WRITER_KEY, "ratelimit-other-writer", false);
		TestTables.putApiKey(dynamoDb, properties.table(), READER_KEY, "ratelimit-reader", false);
	}

	@Test
	void writesBeyondTheOwnersLimitAre429WithRetryAfter() throws Exception {
		for (int i = 0; i < 3; i++) {
			mvc.perform(create(WRITER_KEY, "203.0.113.1")).andExpect(status().isCreated());
		}

		var rejected = mvc.perform(create(WRITER_KEY, "203.0.113.1"))
			.andExpect(status().isTooManyRequests())
			.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
			.andReturn()
			.getResponse();
		assertThat(Long.parseLong(rejected.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 3600L);

		// Reads have their own budget, and other owners aren't affected.
		mvc.perform(withKey(get("/api/v1/urls"), WRITER_KEY, "203.0.113.1")).andExpect(status().isOk());
		mvc.perform(create(OTHER_WRITER_KEY, "203.0.113.2")).andExpect(status().isCreated());
	}

	@Test
	void readsBeyondTheOwnersLimitAre429() throws Exception {
		for (int i = 0; i < 4; i++) {
			mvc.perform(withKey(get("/api/v1/urls"), READER_KEY, "203.0.113.3")).andExpect(status().isOk());
		}

		mvc.perform(withKey(get("/api/v1/urls"), READER_KEY, "203.0.113.3")).andExpect(status().isTooManyRequests());
	}

	@Test
	void clientIpLimitAppliesBeforeTheApiKeyIsChecked() throws Exception {
		for (int i = 0; i < 10; i++) {
			mvc.perform(withKey(get("/api/v1/urls"), "ratelimit-guessed-key-" + i, "203.0.113.4"))
				.andExpect(status().isUnauthorized());
		}

		// The 11th guess from the same address is throttled without reaching the key check.
		mvc.perform(withKey(get("/api/v1/urls"), "ratelimit-guessed-key-10", "203.0.113.4"))
			.andExpect(status().isTooManyRequests());
		mvc.perform(withKey(get("/api/v1/urls"), "ratelimit-guessed-key-11", "203.0.113.5"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void redirectsAreNotRateLimited() throws Exception {
		for (int i = 0; i < 15; i++) {
			mvc.perform(from(get("/ratelimit-nosuchcode"), "203.0.113.6")).andExpect(status().isNotFound());
		}
	}

	private static MockHttpServletRequestBuilder create(String key, String ip) {
		return withKey(post("/api/v1/urls"), key, ip).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"longUrl":"https://www.schwab.com/research"}""");
	}

	private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, String key,
			String ip) {
		return from(request, ip).header(HttpHeaders.AUTHORIZATION, "Bearer " + key);
	}

	private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
		return request.with(servletRequest -> {
			servletRequest.setRemoteAddr(ip);
			return servletRequest;
		});
	}

}
