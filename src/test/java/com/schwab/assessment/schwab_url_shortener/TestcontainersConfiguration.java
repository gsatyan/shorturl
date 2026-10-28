package com.schwab.assessment.schwab_url_shortener;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The same images as docker-compose.yml. Kafka, Redis and ClickHouse connect through service connections; DynamoDB
 * Local has none, so its endpoint is registered as a property. Credentials for the SDK are set by surefire in pom.xml.
 * <p>
 * The containers are shared by every test context in the JVM (one of each, not one per context), and they're
 * stopped by Testcontainers when the JVM exits rather than when a context closes. Tests sharing them must therefore
 * use their own codes, aliases and keys, and create the table only if it doesn't exist ({@link TestTables}). Each
 * context gets its own Kafka consumer group, like each app instance does, so it receives every event.
 * <p>
 * The topics are created at startup, before any listener subscribes, as kafka-init does in docker-compose.yml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final GenericContainer<?> DYNAMODB = new GenericContainer<>(
			DockerImageName.parse("amazon/dynamodb-local:2.6.1"))
		.withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
		.withExposedPorts(8000);

	private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
		.withExposedPorts(6379);

	// The same schema file compose uses, run by the image's own init step.
	private static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer(
			DockerImageName.parse("clickhouse/clickhouse-server:26.3.46.2-alpine"))
		.withDatabaseName("analytics")
		.withCopyFileToContainer(MountableFile.forHostPath("docker/clickhouse/init.sql"),
				"/docker-entrypoint-initdb.d/init.sql");

	@Bean(destroyMethod = "")
	GenericContainer<?> dynamoDbContainer() {
		return DYNAMODB;
	}

	@Bean
	DynamicPropertyRegistrar dynamoDbEndpoint(GenericContainer<?> dynamoDbContainer) {
		return registry -> registry.add("app.dynamodb.endpoint",
				() -> "http://" + dynamoDbContainer.getHost() + ":" + dynamoDbContainer.getMappedPort(8000));
	}

	@Bean
	DynamicPropertyRegistrar kafkaConsumerGroup() {
		var group = "test-" + UUID.randomUUID();
		return registry -> registry.add("spring.kafka.consumer.group-id", () -> group);
	}

	@Bean(destroyMethod = "")
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return KAFKA;
	}

	@Bean
	NewTopic clicksTopic() {
		// 24 h, as kafka-init sets it: raw IPs must not outlive that (docs/DESIGN.md §6.4).
		return new NewTopic("clicks", 3, (short) 1)
			.configs(Map.of(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofHours(24).toMillis())));
	}

	@Bean
	NewTopic linkEventsTopic() {
		return new NewTopic("link-events", 3, (short) 1);
	}

	@Bean(destroyMethod = "")
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return REDIS;
	}

	@Bean(destroyMethod = "")
	@ServiceConnection
	ClickHouseContainer clickHouseContainer() {
		return CLICKHOUSE;
	}

}
