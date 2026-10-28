package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.schwab.assessment.schwab_url_shortener.TestTables;
import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import com.schwab.assessment.schwab_url_shortener.domain.Hashing;
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
 * {@code POST /api/v1/urls} against DynamoDB Local: authentication, generated codes, aliases, idempotency, and the
 * item shape of docs/DESIGN.md §7.1. Each test uses its own aliases and idempotency keys.
 */
@SpringBootTest(properties = "app.links.blocked-hosts=malware.example")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkCreateIntegrationTests {

	private static final String API_KEY = "test-key-owner-1";

	private static final String REVOKED_KEY = "test-key-revoked";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DynamoDbClient dynamoDb;

	@Autowired
	private DynamoDbProperties dynamoDbProperties;

	@BeforeAll
	static void createTableAndKeys(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
		TestTables.putApiKey(dynamoDb, properties.table(), API_KEY, "owner-1", false);
		TestTables.putApiKey(dynamoDb, properties.table(), REVOKED_KEY, "owner-1", true);
	}

	@Test
	void createsGeneratedCodeThatRedirects() throws Exception {
		var result = mvc.perform(create("""
				{"longUrl":"https://www.schwab.com/research","expiresAt":"2030-01-01T00:00:00Z"}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value(matchesPattern("[0-9A-Za-z]{7}")))
			.andExpect(jsonPath("$.longUrl").value("https://www.schwab.com/research"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.expiresAt").value("2030-01-01T00:00:00Z"))
			.andReturn();
		String code = JsonPath.read(result.getResponse().getContentAsString(), "$.code");
		assertThat(result.getResponse().getHeader("Location")).isEqualTo("http://localhost:8080/api/v1/urls/" + code);
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.shortUrl"))
			.isEqualTo("http://localhost:8080/" + code);

		var item = link(code);
		assertThat(item.get("gsi1pk").s()).isEqualTo("OWNER#owner-1");
		assertThat(item.get("gsi1sk").s()).matches("LINK#\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z#" + code);
		assertThat(item.get("ownerId").s()).isEqualTo("owner-1");
		assertThat(item.get("status").s()).isEqualTo("ACTIVE");
		assertThat(item.get("expiresAt").s()).isEqualTo("2030-01-01T00:00:00.000Z");
		assertThat(item.get("version").n()).isEqualTo("1");
		assertThat(item).containsKey("seq");

		mvc.perform(get("/" + code))
			.andExpect(status().isFound())
			.andExpect(header().string("Location", "https://www.schwab.com/research"));
	}

	@Test
	void createsCustomAliasOnceThen409() throws Exception {
		mvc.perform(create("""
				{"longUrl":"https://www.schwab.com/research","customAlias":"q3-research"}"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.code").value("q3-research"));
		assertThat(link("q3-research")).doesNotContainKey("seq");

		mvc.perform(create("""
				{"longUrl":"https://example.com/other","customAlias":"q3-research"}"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("The alias 'q3-research' is already taken"));
		assertThat(link("q3-research").get("longUrl").s()).isEqualTo("https://www.schwab.com/research");
	}

	@Test
	void retryWithSameIdempotencyKeyReturnsTheOriginalLink() throws Exception {
		var body = """
				{"longUrl":"https://www.schwab.com/idempotent"}""";
		var first = mvc.perform(create(body).header("Idempotency-Key", "retry-1"))
			.andExpect(status().isCreated())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andReturn();
		String code = JsonPath.read(first.getResponse().getContentAsString(), "$.code");

		mvc.perform(create(body).header("Idempotency-Key", "retry-1"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.code").value(code));

		var record = dynamoDb.getItem(request -> request.tableName(dynamoDbProperties.table())
			.key(Map.of("pk", AttributeValue.fromS("IDEM#owner-1#" + Hashing.sha256Hex("retry-1")),
					"sk", AttributeValue.fromS("IDEM"))))
			.item();
		assertThat(record.get("ttl").n()).isNotBlank();
	}

	@Test
	void sameIdempotencyKeyWithDifferentBodyIs422() throws Exception {
		mvc.perform(create("""
				{"longUrl":"https://www.schwab.com/a"}""").header("Idempotency-Key", "retry-2"))
			.andExpect(status().isCreated());

		mvc.perform(create("""
				{"longUrl":"https://www.schwab.com/b"}""").header("Idempotency-Key", "retry-2"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void missingUnknownOrRevokedKeyIs401() throws Exception {
		var body = """
				{"longUrl":"https://www.schwab.com/research"}""";
		mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("WWW-Authenticate", "Bearer"));
		mvc.perform(create(body, "not-a-real-key")).andExpect(status().isUnauthorized());
		mvc.perform(create(body, REVOKED_KEY)).andExpect(status().isUnauthorized());
	}

	@Test
	void unsafeOrBlockedDestinationsAreRejected() throws Exception {
		mvc.perform(create("""
				{"longUrl":"javascript:alert(1)"}""")).andExpect(status().isBadRequest());
		mvc.perform(create("""
				{"longUrl":"https://cdn.malware.example/x"}""")).andExpect(status().isUnprocessableContent());
		mvc.perform(create("""
				{"longUrl":"https://example.com","customAlias":"api"}""")).andExpect(status().isBadRequest());
	}

	private static MockHttpServletRequestBuilder create(String body) {
		return create(body, API_KEY);
	}

	private static MockHttpServletRequestBuilder create(String body, String apiKey) {
		return post("/api/v1/urls").header("Authorization", "Bearer " + apiKey)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private Map<String, AttributeValue> link(String code) {
		return dynamoDb.getItem(request -> request.tableName(dynamoDbProperties.table())
			.key(Map.of("pk", AttributeValue.fromS("LINK#" + code), "sk", AttributeValue.fromS("LINK")))
			.consistentRead(true))
			.item();
	}

}
