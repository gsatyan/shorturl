package com.schwab.assessment.schwab_url_shortener.redirect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.schwab.assessment.schwab_url_shortener.TestTables;
import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The whole lookup path against real DynamoDB Local and Redis containers. Each test uses its own code, because the
 * in-process cache is shared by every test in the context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RedirectIntegrationTests {

	private static final String DESTINATION = "https://www.schwab.com/research";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DynamoDbClient dynamoDb;

	@Autowired
	private DynamoDbProperties dynamoDbProperties;

	@Autowired
	private StringRedisTemplate redis;

	@BeforeAll
	static void createTable(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
	}

	@Test
	void activeLinkRedirectsAndIsCachedInRedisForADay() throws Exception {
		putLink("actv001", "ACTIVE", null);

		mvc.perform(get("/actv001")).andExpect(status().isFound()).andExpect(header().string("Location", DESTINATION));

		assertThat(redis.opsForValue().get("link:v1:actv001")).contains(DESTINATION).contains("ACTIVE");
		assertThat(Duration.ofSeconds(redis.getExpire("link:v1:actv001", TimeUnit.SECONDS)))
			.isBetween(Duration.ofHours(23), Duration.ofHours(25));
	}

	@Test
	void unknownCodeIs404AndIsCachedBrieflyInRedis() throws Exception {
		mvc.perform(get("/nope404")).andExpect(status().isNotFound());

		assertThat(redis.getExpire("link:v1:nope404", TimeUnit.SECONDS)).isBetween(1L, 30L);
	}

	@Test
	void disabledLinkIs410() throws Exception {
		putLink("dsbl001", "DISABLED", null);

		mvc.perform(get("/dsbl001")).andExpect(status().isGone());
	}

	@Test
	void expiredLinkIs410() throws Exception {
		putLink("expd001", "ACTIVE", "2020-01-01T00:00:00.000Z");

		mvc.perform(get("/expd001")).andExpect(status().isGone());
	}

	@Test
	void redisEntryIsUsedWithoutReadingDynamoDb() throws Exception {
		// Only in Redis, not in the table: a 302 proves the Redis tier answered.
		redis.opsForValue().set("link:v1:rdis001", """
				{"longUrl":"https://www.schwab.com/from-redis","status":"ACTIVE","expiresAt":null}""");

		mvc.perform(get("/rdis001"))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "https://www.schwab.com/from-redis"));
	}

	private void putLink(String code, String status, String expiresAt) {
		var item = new HashMap<String, AttributeValue>(Map.of(
				"pk", AttributeValue.fromS("LINK#" + code),
				"sk", AttributeValue.fromS("LINK"),
				"code", AttributeValue.fromS(code),
				"longUrl", AttributeValue.fromS(DESTINATION),
				"status", AttributeValue.fromS(status),
				"ownerId", AttributeValue.fromS("owner-1"),
				"createdAt", AttributeValue.fromS("2026-10-27T14:00:00.000Z")));
		if (expiresAt != null) {
			item.put("expiresAt", AttributeValue.fromS(expiresAt));
		}
		dynamoDb.putItem(request -> request.tableName(dynamoDbProperties.table()).item(item));
	}

}
