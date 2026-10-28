package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * {@code GET /api/v1/urls/{code}} and {@code GET /api/v1/urls} against DynamoDB Local (AP6, AP9). Each test uses
 * its own owners, so listings contain only that test's links.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LinkReadIntegrationTests {

	@Autowired
	private MockMvc mvc;

	@BeforeAll
	static void createTableAndKeys(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
		for (var owner : List.of("read-owner-1", "read-owner-2", "read-owner-3", "read-owner-4", "read-owner-5")) {
			TestTables.putApiKey(dynamoDb, properties.table(), owner + "-key", owner, false);
		}
	}

	@Test
	void getReturnsTheCallersLink() throws Exception {
		var code = create("read-owner-1", "https://www.schwab.com/get-test");

		mvc.perform(get("/api/v1/urls/" + code).header("Authorization", "Bearer read-owner-1-key"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.longUrl").value("https://www.schwab.com/get-test"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void anotherOwnersLinkOrAnUnknownCodeIs404() throws Exception {
		var code = create("read-owner-1", "https://www.schwab.com/private");

		mvc.perform(get("/api/v1/urls/" + code).header("Authorization", "Bearer read-owner-2-key"))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/urls/nosuchcode").header("Authorization", "Bearer read-owner-1-key"))
			.andExpect(status().isNotFound());
	}

	@Test
	void listPagesThroughOnlyTheCallersLinksNewestFirst() throws Exception {
		var created = new ArrayList<String>();
		for (int i = 0; i < 5; i++) {
			created.add(create("read-owner-3", "https://www.schwab.com/list-" + i));
		}
		create("read-owner-4", "https://www.schwab.com/someone-else");

		var listed = new ArrayList<String>();
		var createdAts = new ArrayList<Instant>();
		String cursor = null;
		int pages = 0;
		do {
			var request = get("/api/v1/urls").param("limit", "2").header("Authorization", "Bearer read-owner-3-key");
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			var body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			List<String> codes = JsonPath.read(body, "$.items[*].code");
			List<String> times = JsonPath.read(body, "$.items[*].createdAt");
			assertThat(codes).hasSizeLessThanOrEqualTo(2);
			listed.addAll(codes);
			times.forEach(time -> createdAts.add(Instant.parse(time)));
			cursor = JsonPath.read(body, "$.nextCursor");
			pages++;
		}
		while (cursor != null && pages < 10);

		// Only this owner's links, each exactly once; the owner's API key item in the same index partition is skipped.
		assertThat(listed).containsExactlyInAnyOrderElementsOf(created);
		assertThat(pages).as("5 links at 2 per page, with no empty page at the end").isEqualTo(3);
		assertThat(createdAts).isSortedAccordingTo((a, b) -> b.compareTo(a));
	}

	@Test
	void exactlyFullLastPageHasNoNextCursor() throws Exception {
		create("read-owner-5", "https://www.schwab.com/full-page-1");
		create("read-owner-5", "https://www.schwab.com/full-page-2");

		var body = mvc
			.perform(get("/api/v1/urls").param("limit", "2").header("Authorization", "Bearer read-owner-5-key"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat((List<String>) JsonPath.read(body, "$.items[*].code")).hasSize(2);
		assertThat((String) JsonPath.read(body, "$.nextCursor")).isNull();
	}

	@Test
	void invalidLimitOrCursorIs400() throws Exception {
		var key = "Bearer read-owner-1-key";
		mvc.perform(get("/api/v1/urls").param("limit", "0").header("Authorization", key))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/urls").param("limit", "101").header("Authorization", key))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/urls").param("cursor", "tampered.cursor").header("Authorization", key))
			.andExpect(status().isBadRequest());
	}

	@Test
	void cursorFromAnotherOwnerIs400() throws Exception {
		create("read-owner-2", "https://www.schwab.com/cursor-a");
		create("read-owner-2", "https://www.schwab.com/cursor-b");
		var body = mvc
			.perform(get("/api/v1/urls").param("limit", "1").header("Authorization", "Bearer read-owner-2-key"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String cursor = JsonPath.read(body, "$.nextCursor");

		mvc.perform(get("/api/v1/urls").param("cursor", cursor).header("Authorization", "Bearer read-owner-1-key"))
			.andExpect(status().isBadRequest());
	}

	private String create(String owner, String longUrl) throws Exception {
		var body = mvc.perform(post("/api/v1/urls").header("Authorization", "Bearer " + owner + "-key")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"longUrl\":\"" + longUrl + "\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.code");
	}

}
