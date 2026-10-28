package com.schwab.assessment.schwab_url_shortener.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.schwab.assessment.schwab_url_shortener.domain.LinkEvent;
import com.schwab.assessment.schwab_url_shortener.domain.LinkEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * The delivery guarantees of docs/DESIGN.md §6.3 without containers: link changes are published in stream order,
 * a failed publish is read again rather than skipped (at least once), child shards wait for their parent, and an
 * expired iterator resumes after the last published record. {@link LinkInvalidationIntegrationTests} covers the
 * real stream end to end.
 */
class StreamRelayTests {

	private static final String TOPIC = "link-events";

	private static final Instant COMMITTED = Instant.parse("2026-10-28T09:00:00Z");

	private final FakeStreams streams = new FakeStreams();

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

	private final List<LinkEvent> published = new ArrayList<>();

	@Test
	void publishesLinkChangesInStreamOrderAndSkipsOtherItems() {
		streams.openShard("A", null, link(1, OperationType.INSERT, "aZ3kP9q", null, "ACTIVE"),
				item(2, "IDEM#owner#hash", "IDEM"), link(3, OperationType.MODIFY, "aZ3kP9q", "ACTIVE", "DISABLED"));
		kafkaAccepts();

		relay(streams.withTable()).poll();

		assertThat(published).extracting(LinkEvent::type, LinkEvent::code)
			.containsExactly(tuple(LinkEventType.CREATED, "aZ3kP9q"), tuple(LinkEventType.DISABLED, "aZ3kP9q"));
	}

	@Test
	void failedPublishIsReadAgainOnTheNextPoll() {
		streams.openShard("A", null, link(1, OperationType.INSERT, "first01", null, "ACTIVE"),
				link(2, OperationType.INSERT, "second2", null, "ACTIVE"));
		var sends = new AtomicInteger();
		given(kafkaTemplate.send(eq(TOPIC), anyString(), any())).willAnswer(invocation -> {
			// The second send of the first poll fails, as if Kafka went away mid-batch.
			if (sends.incrementAndGet() == 2) {
				return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
			}
			published.add(invocation.getArgument(2));
			return CompletableFuture.completedFuture(null);
		});
		var relay = relay(streams.withTable());

		assertThatIllegalStateException().isThrownBy(relay::poll);
		relay.poll();

		// The whole page is read again from the same iterator: the first event goes out twice, none is lost.
		assertThat(streams.getRecordsIterators).containsExactly("A@0", "A@0");
		assertThat(published).extracting(LinkEvent::code).containsExactly("first01", "first01", "second2");
	}

	@Test
	void childShardStartsOnlyAfterItsParentIsFullyRead() {
		streams.closedShard("parent", null, link(1, OperationType.INSERT, "aZ3kP9q", null, "ACTIVE"));
		streams.openShard("child", "parent", link(2, OperationType.MODIFY, "aZ3kP9q", "ACTIVE", "DISABLED"));
		kafkaAccepts();
		var relay = relay(streams.withTable());

		relay.poll();
		assertThat(streams.iteratorsRequestedFor).containsExactly("parent");
		assertThat(published).extracting(LinkEvent::type).containsExactly(LinkEventType.CREATED);

		relay.poll();
		assertThat(streams.iteratorsRequestedFor).containsExactly("parent", "child");
		assertThat(published).extracting(LinkEvent::type).containsExactly(LinkEventType.CREATED, LinkEventType.DISABLED);
	}

	@Test
	void expiredIteratorResumesAfterTheLastPublishedRecord() {
		streams.pageSize = 1;
		streams.openShard("A", null, link(1, OperationType.INSERT, "first01", null, "ACTIVE"),
				link(2, OperationType.INSERT, "second2", null, "ACTIVE"));
		kafkaAccepts();
		var relay = relay(streams.withTable());

		relay.poll();
		streams.expire("A@1");
		relay.poll();
		relay.poll();

		assertThat(streams.afterSequenceRequests).containsExactly("1");
		assertThat(published).extracting(LinkEvent::code).containsExactly("first01", "second2");
	}

	@Test
	void tableWithoutAStreamIsAnError() {
		assertThatIllegalStateException().isThrownBy(() -> relay(streams.withoutStream()).poll())
			.withMessageContaining("no stream");
	}

	private void kafkaAccepts() {
		given(kafkaTemplate.send(eq(TOPIC), anyString(), any())).willAnswer(invocation -> {
			LinkEvent event = invocation.getArgument(2);
			assertThat((String) invocation.getArgument(1)).as("keyed by code").isEqualTo(event.code());
			published.add(event);
			return CompletableFuture.<SendResult<String, Object>>completedFuture(null);
		});
	}

	private StreamRelay relay(DynamoDbClient dynamoDb) {
		var properties = new RelayProperties(true, Duration.ofMillis(100), Duration.ofSeconds(30));
		return new StreamRelay(properties, "url_shortener", dynamoDb, streams, kafkaTemplate, TOPIC,
				new SimpleMeterRegistry());
	}

	private static Record link(int sequence, OperationType operation, String code, String oldStatus, String newStatus) {
		var keys = Map.of("pk", AttributeValue.fromS("LINK#" + code), "sk", AttributeValue.fromS("LINK"));
		var change = StreamRecord.builder()
			.keys(keys)
			.approximateCreationDateTime(COMMITTED)
			.sequenceNumber(Integer.toString(sequence));
		if (oldStatus != null) {
			change.oldImage(Map.of("pk", keys.get("pk"), "status", AttributeValue.fromS(oldStatus)));
		}
		if (newStatus != null) {
			change.newImage(Map.of("pk", keys.get("pk"), "status", AttributeValue.fromS(newStatus)));
		}
		return Record.builder().eventID("evt-" + sequence).eventName(operation).dynamodb(change.build()).build();
	}

	private static Record item(int sequence, String pk, String sk) {
		var keys = Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk));
		return Record.builder()
			.eventID("evt-" + sequence)
			.eventName(OperationType.INSERT)
			.dynamodb(StreamRecord.builder()
				.keys(keys)
				.newImage(keys)
				.approximateCreationDateTime(COMMITTED)
				.sequenceNumber(Integer.toString(sequence))
				.build())
			.build();
	}

	/**
	 * An in-memory stream. Iterators are {@code <shardId>@<position>}; a read returns up to {@link #pageSize} records
	 * from the position. A closed shard returns no next iterator once it's read to the end.
	 */
	private static final class FakeStreams implements DynamoDbStreamsClient {

		private static final String STREAM_ARN = "arn:aws:dynamodb:local:000000000000:table/url_shortener/stream/1";

		private final List<Shard> shards = new ArrayList<>();

		private final Map<String, List<Record>> records = new HashMap<>();

		private final Set<String> closed = new HashSet<>();

		private final Set<String> expired = new HashSet<>();

		private final List<String> getRecordsIterators = new ArrayList<>();

		private final List<String> iteratorsRequestedFor = new ArrayList<>();

		private final List<String> afterSequenceRequests = new ArrayList<>();

		private int pageSize = 10;

		void openShard(String id, String parent, Record... shardRecords) {
			shards.add(Shard.builder().shardId(id).parentShardId(parent).build());
			records.put(id, List.of(shardRecords));
		}

		void closedShard(String id, String parent, Record... shardRecords) {
			openShard(id, parent, shardRecords);
			closed.add(id);
		}

		void expire(String iterator) {
			expired.add(iterator);
		}

		DynamoDbClient withTable() {
			return table(STREAM_ARN);
		}

		DynamoDbClient withoutStream() {
			return table(null);
		}

		private static DynamoDbClient table(String streamArn) {
			return new DynamoDbClient() {

				@Override
				public DescribeTableResponse describeTable(DescribeTableRequest request) {
					return DescribeTableResponse.builder()
						.table(TableDescription.builder().tableName(request.tableName()).latestStreamArn(streamArn).build())
						.build();
				}

				@Override
				public String serviceName() {
					return "dynamodb";
				}

				@Override
				public void close() {
				}

			};
		}

		@Override
		public DescribeStreamResponse describeStream(DescribeStreamRequest request) {
			return DescribeStreamResponse.builder()
				.streamDescription(StreamDescription.builder().streamArn(request.streamArn()).shards(shards).build())
				.build();
		}

		@Override
		public GetShardIteratorResponse getShardIterator(GetShardIteratorRequest request) {
			var shardId = request.shardId();
			int position = 0;
			if (request.shardIteratorType() == ShardIteratorType.AFTER_SEQUENCE_NUMBER) {
				afterSequenceRequests.add(request.sequenceNumber());
				var shardRecords = records.get(shardId);
				while (!shardRecords.get(position).dynamodb().sequenceNumber().equals(request.sequenceNumber())) {
					position++;
				}
				position++;
			}
			else {
				iteratorsRequestedFor.add(shardId);
			}
			return GetShardIteratorResponse.builder().shardIterator(shardId + "@" + position).build();
		}

		@Override
		public GetRecordsResponse getRecords(GetRecordsRequest request) {
			var iterator = request.shardIterator();
			getRecordsIterators.add(iterator);
			if (expired.remove(iterator)) {
				throw ExpiredIteratorException.builder().message("Iterator expired").build();
			}
			var shardId = iterator.substring(0, iterator.indexOf('@'));
			var position = Integer.parseInt(iterator.substring(iterator.indexOf('@') + 1));
			var shardRecords = records.get(shardId);
			var end = Math.min(position + pageSize, shardRecords.size());
			var next = (closed.contains(shardId) && end == shardRecords.size()) ? null : shardId + "@" + end;
			return GetRecordsResponse.builder()
				.records(shardRecords.subList(position, end))
				.nextShardIterator(next)
				.build();
		}

		@Override
		public String serviceName() {
			return "dynamodb";
		}

		@Override
		public void close() {
		}

	}

}
