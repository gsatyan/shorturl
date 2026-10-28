package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * {@code PATCH} and {@code DELETE /api/v1/urls/{code}} against DynamoDB Local (AP7, AP8). Each test creates
 * its own link through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkUpdateIntegrationTests {

	private static final String OWNER_KEY = "update-test-owner-a";

	private static final String OTHER_OWNER_KEY = "update-test-owner-b";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DynamoDbClient dynamoDb;

	@Autowired
	private DynamoDbProperties dynamoDbProperties;

	@BeforeAll
	static void createTableAndKeys(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
		TestTables.putApiKey(dynamoDb, properties.table(), OWNER_KEY, "owner-a", false);
		TestTables.putApiKey(dynamoDb, properties.table(), OTHER_OWNER_KEY, "owner-b", false);
	}

	@Test
	void disableThenReEnable() throws Exception {
		var code = createLink();

		mvc.perform(patchLink(code, """
				{"status":"DISABLED"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISABLED"));
		assertThat(item(code).get("version").n()).isEqualTo("2");
		mvc.perform(get("/" + code)).andExpect(status().isGone());

		mvc.perform(patchLink(code, """
				{"status":"ACTIVE"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		assertThat(item(code).get("version").n()).isEqualTo("3");
	}

	@Test
	void everyChangeWritesAnAuditEntry() throws Exception {
		var code = createLink();

		mvc.perform(patchLink(code, """
				{"status":"DISABLED","expiresAt":"2031-06-01T00:00:00Z"}""")).andExpect(status().isOk());

		var audit = dynamoDb.query(request -> request.tableName(dynamoDbProperties.table())
			.keyConditionExpression("pk = :pk AND begins_with(sk, :audit)")
			.expressionAttributeValues(Map.of(":pk", AttributeValue.fromS("LINK#" + code),
					":audit", AttributeValue.fromS("AUDIT#"))))
			.items();
		assertThat(audit).singleElement().satisfies(entry -> {
			assertThat(entry.get("action").s()).isEqualTo("DISABLED");
			assertThat(entry.get("actor").s()).isEqualTo("owner-a");
			assertThat(entry.get("changes").m().get("status").m())
				.containsEntry("old", AttributeValue.fromS("ACTIVE"))
				.containsEntry("new", AttributeValue.fromS("DISABLED"));
			assertThat(entry.get("changes").m().get("expiresAt").m())
				.containsEntry("old", AttributeValue.fromS("2030-01-01T00:00:00.000Z"))
				.containsEntry("new", AttributeValue.fromS("2031-06-01T00:00:00.000Z"));
		});
	}

	@Test
	void expiryCanMoveToAnotherFutureTimeButNotBeRemovedOrPast() throws Exception {
		var code = createLink();

		mvc.perform(patchLink(code, """
				{"expiresAt":"2032-01-01T00:00:00Z"}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expiresAt").value("2032-01-01T00:00:00Z"));
		mvc.perform(patchLink(code, """
				{"expiresAt":null}""")).andExpect(status().isBadRequest());
		mvc.perform(patchLink(code, """
				{"expiresAt":"2020-01-01T00:00:00Z"}""")).andExpect(status().isBadRequest());
		assertThat(item(code).get("expiresAt").s()).isEqualTo("2032-01-01T00:00:00.000Z");
	}

	@Test
	void deleteDisablesAndIsSafeToRepeat() throws Exception {
		var code = createLink();

		mvc.perform(deleteLink(code)).andExpect(status().isNoContent());
		mvc.perform(deleteLink(code)).andExpect(status().isNoContent());

		assertThat(item(code).get("status").s()).isEqualTo("DISABLED");
		assertThat(item(code).get("version").n()).isEqualTo("2");   // the repeat wrote nothing
		mvc.perform(get("/" + code)).andExpect(status().isGone());
	}

	@Test
	void anotherOwnersLinkOrAnUnknownCodeIs404() throws Exception {
		var code = createLink();

		mvc.perform(patchLink(code, """
				{"status":"DISABLED"}""", OTHER_OWNER_KEY))
			.andExpect(status().isNotFound());
		mvc.perform(deleteLink("nosuchcode"))
			.andExpect(status().isNotFound());
		assertThat(item(code).get("status").s()).isEqualTo("ACTIVE");
	}

	@Test
	void blockedLinkCantBeChangedByItsOwner() throws Exception {
		var code = createLink();
		dynamoDb.updateItem(request -> request.tableName(dynamoDbProperties.table())
			.key(LinkItemMapper.key(code))
			.updateExpression("SET #status = :blocked")
			.expressionAttributeNames(Map.of("#status", "status"))
			.expressionAttributeValues(Map.of(":blocked", AttributeValue.fromS("BLOCKED"))));

		mvc.perform(patchLink(code, """
				{"status":"ACTIVE"}"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("This link is BLOCKED and can't be changed"));
		mvc.perform(deleteLink(code)).andExpect(status().isConflict());
	}

	@Test
	void destinationCantBeChanged() throws Exception {
		var code = createLink();

		mvc.perform(patchLink(code, """
				{"longUrl":"https://evil.example"}""")).andExpect(status().isBadRequest());
	}

	private String createLink() throws Exception {
		var result = mvc.perform(post("/api/v1/urls").header("Authorization", "Bearer " + OWNER_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"longUrl":"https://www.schwab.com/update-test","expiresAt":"2030-01-01T00:00:00Z"}"""))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
	}

	private static MockHttpServletRequestBuilder patchLink(String code, String body) {
		return patchLink(code, body, OWNER_KEY);
	}

	private static MockHttpServletRequestBuilder patchLink(String code, String body, String apiKey) {
		return patch("/api/v1/urls/" + code).header("Authorization", "Bearer " + apiKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static MockHttpServletRequestBuilder deleteLink(String code) {
		return delete("/api/v1/urls/" + code).header("Authorization", "Bearer " + OWNER_KEY);
	}

	private Map<String, AttributeValue> item(String code) {
		return dynamoDb.getItem(request -> request.tableName(dynamoDbProperties.table())
			.key(LinkItemMapper.key(code))
			.consistentRead(true))
			.item();
	}

}
