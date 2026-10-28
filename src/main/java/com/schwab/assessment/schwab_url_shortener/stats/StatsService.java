package com.schwab.assessment.schwab_url_shortener.stats;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import com.schwab.assessment.schwab_url_shortener.domain.ApiException;
import com.schwab.assessment.schwab_url_shortener.stats.StatsQuery.Granularity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Click analytics for one link (AP22, docs/DESIGN.md §7.2): checks ownership in DynamoDB (AP6), then aggregates in
 * ClickHouse. Ownership is read here rather than through the link package, so stats stays separable into its own
 * service (§5.1).
 */
@Service
class StatsService {

	static final int TOP_REFERRERS = 10;

	static final Duration MAX_DAY_RANGE = Duration.ofDays(366);

	static final Duration MAX_HOUR_RANGE = Duration.ofDays(31);

	private final StatsRepository repository;

	private final DynamoDbClient dynamoDb;

	private final String table;

	private final Clock clock;

	@Autowired
	StatsService(StatsRepository repository, DynamoDbClient dynamoDb, DynamoDbProperties properties) {
		this(repository, dynamoDb, properties, Clock.systemUTC());
	}

	StatsService(StatsRepository repository, DynamoDbClient dynamoDb, DynamoDbProperties properties, Clock clock) {
		this.repository = repository;
		this.dynamoDb = dynamoDb;
		this.table = properties.table();
		this.clock = clock;
	}

	/**
	 * @param from default: 30 days (DAY) or 24 hours (HOUR) before {@code to}
	 * @param to   default: now
	 */
	LinkStats stats(String ownerId, String code, Instant from, Instant to, Granularity granularity,
			boolean includeBots) {
		var end = (to != null) ? to : clock.instant();
		var start = (from != null) ? from : end.minus((granularity == Granularity.HOUR) ? Duration.ofHours(24)
				: Duration.ofDays(30));
		if (!start.isBefore(end)) {
			throw badRequest("from must be before to");
		}
		var max = (granularity == Granularity.HOUR) ? MAX_HOUR_RANGE : MAX_DAY_RANGE;
		if (Duration.between(start, end).compareTo(max) > 0) {
			throw badRequest("The range can be at most " + max.toDays() + " days for " + granularity + " granularity");
		}
		checkOwner(ownerId, code);

		var query = new StatsQuery(code, start, end, granularity, includeBots);
		var totals = repository.totals(query);
		return new LinkStats(code, totals[0], totals[1], fill(repository.series(query), query),
				repository.topReferrers(query, TOP_REFERRERS), repository.byDevice(query), repository.byCountry(query));
	}

	/** Another owner's link is {@code 404}, like an unknown code, so codes can't be probed (§4). */
	private void checkOwner(String ownerId, String code) {
		var item = dynamoDb.getItem(request -> request.tableName(table)
			.key(Map.of("pk", AttributeValue.fromS("LINK#" + code), "sk", AttributeValue.fromS("LINK")))
			.projectionExpression("ownerId")
			.consistentRead(true))
			.item();
		var owner = (item == null) ? null : item.get("ownerId");
		if (owner == null || !owner.s().equals(ownerId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "No link with code '" + code + "'");
		}
	}

	/** Every bucket from the one containing {@code from} up to {@code to}, so charts don't skip quiet days. */
	private static List<LinkStats.Bucket> fill(Map<Instant, Long> counts, StatsQuery query) {
		var unit = (query.granularity() == Granularity.HOUR) ? ChronoUnit.HOURS : ChronoUnit.DAYS;
		var buckets = new ArrayList<LinkStats.Bucket>();
		for (var bucket = query.from().truncatedTo(unit); bucket.isBefore(query.to()); bucket = bucket.plus(1, unit)) {
			var label = (unit == ChronoUnit.DAYS) ? bucket.atZone(ZoneOffset.UTC).toLocalDate().toString()
					: bucket.toString();
			buckets.add(new LinkStats.Bucket(label, counts.getOrDefault(bucket, 0L)));
		}
		return buckets;
	}

	private static ApiException badRequest(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, detail);
	}

}
