package com.schwab.assessment.schwab_url_shortener.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.schwab.assessment.schwab_url_shortener.domain.LinkEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.KafkaTemplate;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * Reads the table's DynamoDB Stream and publishes each link change to {@code link-events} (docs/DESIGN.md §6.3).
 * <p>
 * DynamoDB writes a change to the stream as part of committing it, so reading the stream can't miss a committed
 * change, unlike sending the event from the service after its write. A batch is published, and waits for Kafka's
 * acknowledgement, before the relay moves past it: a crash or a Kafka outage means the batch is read and sent
 * again (at least once), never skipped.
 * <p>
 * Progress is kept in memory. On start the relay reads each shard from its oldest record (the stream keeps 24 hours),
 * so changes made while it was down are still published; the replays only cause harmless extra cache evictions.
 * Shards are processed after their parent shard, which keeps each link's changes in order.
 */
class StreamRelay implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(StreamRelay.class);

	private final RelayProperties properties;

	private final String table;

	private final DynamoDbClient dynamoDb;

	private final DynamoDbStreamsClient streams;

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private final String topic;

	private final Counter published;

	private final Counter errors;

	private final AtomicLong lagMillis = new AtomicLong();

	/** Open shards being read: shard ID → next iterator. Insertion order keeps parents ahead of children. */
	private final Map<String, String> iterators = new LinkedHashMap<>();

	/** Last sequence number published per shard, to resume after an iterator expires. */
	private final Map<String, String> lastSequence = new HashMap<>();

	private final Set<String> finishedShards = new HashSet<>();

	private ScheduledExecutorService executor;

	private String streamArn;

	private Instant nextShardRefresh = Instant.MIN;

	StreamRelay(RelayProperties properties, String table, DynamoDbClient dynamoDb, DynamoDbStreamsClient streams,
			KafkaTemplate<String, Object> kafkaTemplate, String topic, MeterRegistry meterRegistry) {
		this.properties = properties;
		this.table = table;
		this.dynamoDb = dynamoDb;
		this.streams = streams;
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
		this.published = Counter.builder("relay.events.published").register(meterRegistry);
		this.errors = Counter.builder("relay.errors").register(meterRegistry);
		Gauge.builder("relay.lag", lagMillis, millis -> millis.get() / 1000.0)
			.description("Age of the newest stream record in the last batch; 0 when caught up")
			.baseUnit("seconds")
			.register(meterRegistry);
	}

	@Override
	public void start() {
		executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "stream-relay"));
		executor.scheduleWithFixedDelay(this::pollSafely, 0, properties.pollInterval().toMillis(),
				TimeUnit.MILLISECONDS);
		log.info("Stream relay started for table {}", table);
	}

	@Override
	public void stop() {
		executor.shutdownNow();
	}

	@Override
	public boolean isRunning() {
		return executor != null && !executor.isShutdown();
	}

	private void pollSafely() {
		try {
			poll();
		}
		catch (RuntimeException failure) {
			// Nothing was skipped: the failed batch is read again on the next poll.
			errors.increment();
			log.warn("Stream relay poll failed; retrying", failure);
		}
	}

	void poll() {
		if (streamArn == null) {
			streamArn = dynamoDb.describeTable(request -> request.tableName(table)).table().latestStreamArn();
			if (streamArn == null) {
				throw new IllegalStateException("Table " + table + " has no stream enabled");
			}
		}
		if (Instant.now().isAfter(nextShardRefresh)) {
			refreshShards();
		}
		Instant newestRecord = null;
		for (var shardId : Set.copyOf(iterators.keySet())) {
			var newest = pollShard(shardId);
			if (newest != null && (newestRecord == null || newest.isAfter(newestRecord))) {
				newestRecord = newest;
			}
		}
		lagMillis.set((newestRecord == null) ? 0 : Duration.between(newestRecord, Instant.now()).toMillis());
	}

	private void refreshShards() {
		var shards = new LinkedHashMap<String, Shard>();
		String lastShardId = null;
		do {
			var startAfter = lastShardId;
			var description = streams
				.describeStream(request -> request.streamArn(streamArn).exclusiveStartShardId(startAfter))
				.streamDescription();
			description.shards().forEach(shard -> shards.put(shard.shardId(), shard));
			lastShardId = description.lastEvaluatedShardId();
		}
		while (lastShardId != null);

		for (var shard : shards.values()) {
			var id = shard.shardId();
			var parent = shard.parentShardId();
			var parentDone = parent == null || finishedShards.contains(parent) || !shards.containsKey(parent);
			if (!iterators.containsKey(id) && !finishedShards.contains(id) && parentDone) {
				iterators.put(id, iterator(id, ShardIteratorType.TRIM_HORIZON, null));
			}
		}
		nextShardRefresh = Instant.now().plus(properties.shardRefresh());
	}

	/** @return creation time of the newest record published, or {@code null} if the shard had none */
	private Instant pollShard(String shardId) {
		GetRecordsResult result;
		try {
			result = getRecords(iterators.get(shardId));
		}
		catch (ExpiredIteratorException expired) {
			// Iterators expire after 15 minutes unused, e.g. after a long Kafka outage. Resume after the last
			// published record.
			var last = lastSequence.get(shardId);
			iterators.put(shardId, (last == null) ? iterator(shardId, ShardIteratorType.TRIM_HORIZON, null)
					: iterator(shardId, ShardIteratorType.AFTER_SEQUENCE_NUMBER, last));
			return null;
		}

		Instant newest = null;
		for (var record : result.records()) {
			StreamRecordMapper.toLinkEvent(record).ifPresent(this::publish);
			lastSequence.put(shardId, record.dynamodb().sequenceNumber());
			newest = record.dynamodb().approximateCreationDateTime();
		}

		if (result.nextIterator() == null) {
			// The shard is closed and fully read; its children can start.
			iterators.remove(shardId);
			lastSequence.remove(shardId);
			finishedShards.add(shardId);
			nextShardRefresh = Instant.MIN;
		}
		else {
			iterators.put(shardId, result.nextIterator());
		}
		return newest;
	}

	private void publish(LinkEvent event) {
		try {
			kafkaTemplate.send(topic, event.code(), event).get(10, TimeUnit.SECONDS);
			published.increment();
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while publishing " + event, interrupted);
		}
		catch (ExecutionException | TimeoutException failure) {
			throw new IllegalStateException("Couldn't publish " + event, failure);
		}
	}

	private GetRecordsResult getRecords(String iterator) {
		var response = streams.getRecords(request -> request.shardIterator(iterator).limit(1000));
		return new GetRecordsResult(response.records(), response.nextShardIterator());
	}

	private String iterator(String shardId, ShardIteratorType type, String sequenceNumber) {
		return streams.getShardIterator(request -> request.streamArn(streamArn)
			.shardId(shardId)
			.shardIteratorType(type)
			.sequenceNumber(sequenceNumber))
			.shardIterator();
	}

	private record GetRecordsResult(List<Record> records, String nextIterator) {
	}

}
