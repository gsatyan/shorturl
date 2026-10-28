package com.schwab.assessment.schwab_url_shortener.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.schwab.assessment.schwab_url_shortener.TestTables;
import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The whole analytics path of docs/DESIGN.md §6.4 on real containers: redirects publish click events, the click
 * processor enriches them into ClickHouse, and the stats endpoint aggregates them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatsIntegrationTests {

	private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Mobile/15E148";

	private static final String MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 Safari/605.1.15";

	private static final String SLACKBOT = "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate clickHouse;

	@BeforeAll
	static void createTableAndKeys(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
		TestTables.putApiKey(dynamoDb, properties.table(), "stats-owner-key", "stats-owner", false);
		TestTables.putApiKey(dynamoDb, properties.table(), "stats-other-key", "stats-other", false);
	}

	@Test
	void redirectsShowUpInStats() throws Exception {
		var code = createLink();
		click(code, IPHONE, "73.15.0.1", "https://www.linkedin.com/feed/");
		click(code, IPHONE, "73.15.0.1", "https://www.linkedin.com/feed/");   // same visitor again
		click(code, MAC, "73.15.0.2", "https://t.co/xyz");
		click(code, MAC, "73.15.0.3", null);
		click(code, SLACKBOT, "34.1.1.1", null);                               // a link preview, not a person

		var body = awaitClicks(code, 5);   // includeBots=true sees all 5

		assertThat((Integer) JsonPath.read(body, "$.totalClicks")).isEqualTo(5);
		assertThat((Map<String, Integer>) JsonPath.read(body, "$.byDeviceType"))
			.containsExactlyInAnyOrderEntriesOf(Map.of("MOBILE", 2, "DESKTOP", 2, "BOT", 1));

		var humans = stats(code, false).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((Integer) JsonPath.read(humans, "$.totalClicks")).isEqualTo(4);
		assertThat((Integer) JsonPath.read(humans, "$.uniqueVisitors")).isEqualTo(3);
		assertThat((List<String>) JsonPath.read(humans, "$.topReferrers[*].host")).containsExactly("linkedin.com", "t.co");
		assertThat((List<Integer>) JsonPath.read(humans, "$.topReferrers[*].clicks")).containsExactly(2, 1);
		assertThat((Map<String, Integer>) JsonPath.read(humans, "$.byCountry")).isEmpty();
		var today = LocalDate.now(ZoneOffset.UTC).toString();
		assertThat((List<Integer>) JsonPath.read(humans, "$.series[?(@.bucket == '" + today + "')].clicks"))
			.containsExactly(4);
		assertThat((List<Object>) JsonPath.read(humans, "$.series")).hasSize(31);   // 30 days back, zero days included
	}

	@Test
	void storedRowsHoldNoRawIpOrUserAgent() throws Exception {
		var code = createLink();
		click(code, IPHONE, "198.51.100.77", "https://www.linkedin.com/feed/?token=abc");
		awaitClicks(code, 1);

		var row = clickHouse.queryForMap(
				"SELECT referrer, device, hex(visitor_hash) AS visitor FROM analytics.clicks_raw WHERE code = ?", code);

		assertThat(row).containsEntry("referrer", "linkedin.com").containsEntry("device", "MOBILE");
		assertThat(row.toString()).doesNotContain("198.51.100.77").doesNotContain("iPhone").doesNotContain("token");
	}

	@Test
	void hourlySeriesHasOneBucketPerHour() throws Exception {
		var code = createLink();
		var to = Instant.now();
		var from = to.minus(Duration.ofHours(6));

		var body = mvc.perform(withKey(get("/api/v1/urls/" + code + "/stats").param("granularity", "HOUR")
			.param("from", from.toString())
			.param("to", to.toString()), "stats-owner-key")).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();

		assertThat((List<String>) JsonPath.read(body, "$.series[*].bucket")).hasSize(7)
			.allMatch(bucket -> bucket.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:00:00Z"));
	}

	@Test
	void anotherOwnerOrAnUnknownCodeIs404() throws Exception {
		var code = createLink();

		mvc.perform(withKey(get("/api/v1/urls/" + code + "/stats"), "stats-other-key")).andExpect(status().isNotFound());
		mvc.perform(withKey(get("/api/v1/urls/nosuchcode/stats"), "stats-owner-key")).andExpect(status().isNotFound());
	}

	@Test
	void invalidRangesAre400() throws Exception {
		var code = createLink();
		var now = Instant.now();

		mvc.perform(withKey(get("/api/v1/urls/" + code + "/stats").param("from", now.toString())
			.param("to", now.minusSeconds(60).toString()), "stats-owner-key")).andExpect(status().isBadRequest());
		mvc.perform(withKey(get("/api/v1/urls/" + code + "/stats").param("granularity", "HOUR")
			.param("from", now.minus(Duration.ofDays(40)).toString()), "stats-owner-key"))
			.andExpect(status().isBadRequest());
		mvc.perform(withKey(get("/api/v1/urls/" + code + "/stats").param("granularity", "WEEK"), "stats-owner-key"))
			.andExpect(status().isBadRequest());
	}

	private String createLink() throws Exception {
		var body = mvc.perform(withKey(post("/api/v1/urls"), "stats-owner-key").contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"longUrl":"https://www.schwab.com/stats-test"}"""))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.code");
	}

	private void click(String code, String userAgent, String ip, String referer) throws Exception {
		var request = get("/" + code).header("User-Agent", userAgent).with(r -> {
			r.setRemoteAddr(ip);
			return r;
		});
		if (referer != null) {
			request.header("Referer", referer);
		}
		mvc.perform(request).andExpect(status().isFound());
	}

	/** Clicks reach ClickHouse asynchronously, through Kafka and the click processor. */
	private String awaitClicks(String code, int expected) throws Exception {
		var deadline = Instant.now().plus(Duration.ofSeconds(30));
		String body;
		int total;
		do {
			body = stats(code, true).andReturn().getResponse().getContentAsString();
			total = JsonPath.read(body, "$.totalClicks");
			if (total < expected) {
				Thread.sleep(250);
			}
		}
		while (total < expected && Instant.now().isBefore(deadline));
		assertThat(total).as("clicks recorded for %s", code).isEqualTo(expected);
		return body;
	}

	private ResultActions stats(String code, boolean includeBots) throws Exception {
		return mvc.perform(withKey(get("/api/v1/urls/" + code + "/stats").param("includeBots", Boolean.toString(includeBots)),
				"stats-owner-key"));
	}

	private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, String key) {
		return request.header("Authorization", "Bearer " + key);
	}

}
