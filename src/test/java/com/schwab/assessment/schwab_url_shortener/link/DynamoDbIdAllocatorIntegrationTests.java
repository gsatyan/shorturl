package com.schwab.assessment.schwab_url_shortener.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import com.schwab.assessment.schwab_url_shortener.TestTables;
import com.schwab.assessment.schwab_url_shortener.TestcontainersConfiguration;
import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * AP5 (docs/DESIGN.md §6.1) against DynamoDB Local. Other test classes lease from the same counter item, so these
 * tests check relationships between IDs and counter values, never absolute numbers.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DynamoDbIdAllocatorIntegrationTests {

	/** Small, so a test crosses block boundaries in a few calls. */
	private static final int BLOCK_SIZE = 5;

	@Autowired
	private DynamoDbClient dynamoDb;

	@Autowired
	private DynamoDbProperties dynamoDbProperties;

	@Autowired
	private LinksProperties linksProperties;

	@BeforeAll
	static void createTable(@Autowired DynamoDbClient dynamoDb, @Autowired DynamoDbProperties properties) {
		TestTables.createUrlShortenerTable(dynamoDb, properties.table());
	}

	@Test
	void idsAreConsecutiveWithinABlockAndLeasedOncePerBlock() {
		var allocator = allocator();
		var first = allocator.nextId();
		var counterAfterLease = counter();
		// The lease returned the block's end, so the block is (end − size, end] and the first ID is its start.
		assertThat(first).isEqualTo(counterAfterLease - BLOCK_SIZE + 1);

		var rest = LongStream.range(1, BLOCK_SIZE).map(i -> allocator.nextId()).toArray();
		assertThat(rest).containsExactly(LongStream.rangeClosed(first + 1, counterAfterLease).toArray());
		assertThat(counter()).as("no database call inside a block").isEqualTo(counterAfterLease);

		var nextBlockStart = allocator.nextId();
		assertThat(nextBlockStart).isGreaterThan(counterAfterLease);
		assertThat(counter()).isGreaterThanOrEqualTo(counterAfterLease + BLOCK_SIZE);
	}

	@Test
	void twoInstancesNeverHandOutTheSameId() {
		var one = allocator();
		var two = allocator();
		var ids = new ArrayList<Long>();
		for (int i = 0; i < 3 * BLOCK_SIZE; i++) {
			ids.add(one.nextId());
			ids.add(two.nextId());
		}

		assertThat(ids).doesNotHaveDuplicates();
	}

	@Test
	void concurrentCallersOnOneInstanceGetUniqueIds() throws Exception {
		var allocator = allocator();
		Set<Long> ids = ConcurrentHashMap.newKeySet();
		var perThread = 4 * BLOCK_SIZE;
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < 8; t++) {
				futures.add(executor.submit(() -> {
					for (int i = 0; i < perThread; i++) {
						ids.add(allocator.nextId());
					}
				}));
			}
			for (var future : futures) {
				future.get();
			}
		}

		assertThat(ids).hasSize(8 * perThread);
	}

	private DynamoDbIdAllocator allocator() {
		var properties = new LinksProperties(linksProperties.baseUrl(), linksProperties.blockedHosts(),
				linksProperties.reservedAliases(), BLOCK_SIZE);
		return new DynamoDbIdAllocator(dynamoDb, dynamoDbProperties, properties);
	}

	private long counter() {
		var item = dynamoDb.getItem(request -> request.tableName(dynamoDbProperties.table())
			.key(Map.of("pk", AttributeValue.fromS("COUNTER#short_url"), "sk", AttributeValue.fromS("COUNTER")))
			.consistentRead(true))
			.item();
		return Long.parseLong(item.get("value").n());
	}

}
