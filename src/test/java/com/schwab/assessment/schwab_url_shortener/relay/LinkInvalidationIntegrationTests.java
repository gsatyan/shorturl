package com.schwab.assessment.schwab_url_shortener.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The whole invalidation path of docs/DESIGN.md §6.3 on real containers: a link is cached in both tiers, its item
 * is changed directly in DynamoDB, and the stream relay, Kafka and the listener must clear both caches so the next
 * redirect sees the change, long before the 60 s in-process or 24 h Redis TTLs would.
 */
@SpringBootTest(properties = { "app.relay.enabled=true", "app.relay.poll-interval=200ms" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkInvalidationIntegrationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DynamoDbClient dynamoDb;

	@Autowired
	private DynamoDbProperties dynamoDbProperties;

	@Autowired
	private StringRedisTemplate redis;

	@BeforeAll
	static void createTableAndWaitForListener(@Autowired DynamoDbClient dynamoDb,
			@Autowired DynamoDbProperties properties, @Autowired KafkaListenerEndpointRegistry listeners) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
		// The listener starts at the latest offset, so it must own its partitions before any event is published.
		listeners.getListenerContainers()
			.forEach(container -> ContainerTestUtils.waitForAssignment(container, 3));
	}

	@Test
	void disablingALinkInDynamoDbClearsBothCaches() throws Exception {
		dynamoDb.putItem(request -> request.tableName(dynamoDbProperties.table())
			.item(Map.of("pk", AttributeValue.fromS("LINK#inval01"), "sk", AttributeValue.fromS("LINK"),
					"longUrl", AttributeValue.fromS("https://www.schwab.com/research"),
					"status", AttributeValue.fromS("ACTIVE"))));
		mvc.perform(get("/inval01")).andExpect(status().isFound());
		mvc.perform(get("/inval01")).andExpect(status().isFound());   // now served from the in-process cache
		assertThat(redis.hasKey("link:v1:inval01")).isTrue();

		dynamoDb.updateItem(request -> request.tableName(dynamoDbProperties.table())
			.key(Map.of("pk", AttributeValue.fromS("LINK#inval01"), "sk", AttributeValue.fromS("LINK")))
			.updateExpression("SET #status = :disabled")
			.expressionAttributeNames(Map.of("#status", "status"))
			.expressionAttributeValues(Map.of(":disabled", AttributeValue.fromS("DISABLED"))));

		var deadline = Instant.now().plus(Duration.ofSeconds(20));
		int status;
		do {
			Thread.sleep(200);
			status = mvc.perform(get("/inval01")).andReturn().getResponse().getStatus();
		}
		while (status != 410 && Instant.now().isBefore(deadline));
		assertThat(status).as("redirect status after the link was disabled").isEqualTo(410);
	}

}
