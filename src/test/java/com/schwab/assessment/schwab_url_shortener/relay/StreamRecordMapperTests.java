package com.schwab.assessment.schwab_url_shortener.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import com.schwab.assessment.schwab_url_shortener.domain.LinkEvent;
import com.schwab.assessment.schwab_url_shortener.domain.LinkEventType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

class StreamRecordMapperTests {

	private static final Instant COMMITTED = Instant.parse("2026-10-27T20:00:00Z");

	@Test
	void insertedLinkIsCreated() {
		var event = StreamRecordMapper.toLinkEvent(record(OperationType.INSERT, "LINK#aZ3kP9q", "LINK", null, "ACTIVE"));

		assertThat(event).contains(new LinkEvent("evt-1", LinkEventType.CREATED, "aZ3kP9q", COMMITTED));
	}

	@Test
	void statusChangesMapToTheirEventType() {
		assertThat(type("ACTIVE", "DISABLED")).isEqualTo(LinkEventType.DISABLED);
		assertThat(type("ACTIVE", "BLOCKED")).isEqualTo(LinkEventType.BLOCKED);
		assertThat(type("DISABLED", "DELETED")).isEqualTo(LinkEventType.DELETED);
		assertThat(type("DISABLED", "ACTIVE")).isEqualTo(LinkEventType.UPDATED);
	}

	@Test
	void changeWithoutStatusChangeIsUpdated() {
		// e.g. a new expiresAt
		assertThat(type("ACTIVE", "ACTIVE")).isEqualTo(LinkEventType.UPDATED);
	}

	@Test
	void removedItemIsDeleted() {
		var event = StreamRecordMapper.toLinkEvent(record(OperationType.REMOVE, "LINK#aZ3kP9q", "LINK", "ACTIVE", null));

		assertThat(event).map(LinkEvent::type).contains(LinkEventType.DELETED);
	}

	@Test
	void itemsThatArentLinksAreSkipped() {
		assertThat(StreamRecordMapper.toLinkEvent(record(OperationType.INSERT, "LINK#aZ3kP9q", "AUDIT#2026#1", null, null)))
			.isEmpty();
		assertThat(StreamRecordMapper.toLinkEvent(record(OperationType.INSERT, "IDEM#owner#hash", "IDEM", null, null)))
			.isEmpty();
		assertThat(StreamRecordMapper.toLinkEvent(record(OperationType.MODIFY, "COUNTER#short_url", "COUNTER", null, null)))
			.isEmpty();
	}

	private static LinkEventType type(String oldStatus, String newStatus) {
		return StreamRecordMapper.toLinkEvent(record(OperationType.MODIFY, "LINK#aZ3kP9q", "LINK", oldStatus, newStatus))
			.orElseThrow()
			.type();
	}

	private static Record record(OperationType operation, String pk, String sk, String oldStatus, String newStatus) {
		var keys = Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk));
		var change = StreamRecord.builder()
			.keys(keys)
			.approximateCreationDateTime(COMMITTED)
			.sequenceNumber("100");
		if (oldStatus != null) {
			change.oldImage(Map.of("pk", keys.get("pk"), "status", AttributeValue.fromS(oldStatus)));
		}
		if (newStatus != null) {
			change.newImage(Map.of("pk", keys.get("pk"), "status", AttributeValue.fromS(newStatus)));
		}
		return Record.builder().eventID("evt-1").eventName(operation).dynamodb(change.build()).build();
	}

}
