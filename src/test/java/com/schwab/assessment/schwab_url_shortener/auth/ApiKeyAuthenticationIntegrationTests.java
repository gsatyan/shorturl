package com.schwab.assessment.schwab_url_shortener.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schwab.assessment.schwab_url_shortener.TestTables;
import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Every management and stats endpoint rejects a missing, unknown or revoked API key with {@code 401} before doing
 * any work (docs/DESIGN.md §4, AP11), while redirects and health stay public. The codes don't need to exist: the
 * key is checked before the handler runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiKeyAuthenticationIntegrationTests {

	private static final String REVOKED_KEY = "auth-test-revoked-key";

	@Autowired
	private MockMvc mvc;

	@BeforeAll
	static void createTableAndKeys(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
		TestTables.putApiKey(dynamoDb, properties.table(), REVOKED_KEY, "auth-test-owner", true);
	}

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({
		"POST,   /api/v1/urls",
		"GET,    /api/v1/urls",
		"GET,    /api/v1/urls/authtest1",
		"PATCH,  /api/v1/urls/authtest1",
		"DELETE, /api/v1/urls/authtest1",
		"GET,    /api/v1/urls/authtest1/stats",
	})
	void missingUnknownOrRevokedKeyIs401(HttpMethod method, String path) throws Exception {
		mvc.perform(call(method, path))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		mvc.perform(call(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer ")).andExpect(status().isUnauthorized());
		mvc.perform(call(method, path).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
			.andExpect(status().isUnauthorized());
		mvc.perform(call(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer auth-test-unknown-key"))
			.andExpect(status().isUnauthorized());
		mvc.perform(call(method, path).header(HttpHeaders.AUTHORIZATION, "Bearer " + REVOKED_KEY))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void redirectsAndHealthNeedNoKey() throws Exception {
		mvc.perform(call(HttpMethod.GET, "/authtest-nosuchcode")).andExpect(status().isNotFound());
		mvc.perform(call(HttpMethod.GET, "/actuator/health")).andExpect(status().isOk());
	}

	private static MockHttpServletRequestBuilder call(HttpMethod method, String path) {
		// A valid-looking body, so a 401 can only come from the missing key.
		return request(method, path).contentType(MediaType.APPLICATION_JSON).content("{}");
	}

}
