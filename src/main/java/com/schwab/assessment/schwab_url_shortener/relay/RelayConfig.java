package com.schwab.assessment.schwab_url_shortener.relay;

import java.net.URI;

import com.schwab.assessment.schwab_url_shortener.config.DynamoDbProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * Turns the stream relay on for this instance only when {@code app.relay.enabled=true}. Locally that's the single
 * {@code relay} container in docker-compose.yml: the same image as the redirect replicas, which leave it off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("app.relay.enabled")
@EnableConfigurationProperties(RelayProperties.class)
class RelayConfig {

	@Bean(destroyMethod = "close")
	DynamoDbStreamsClient dynamoDbStreamsClient(DynamoDbProperties properties) {
		var builder = DynamoDbStreamsClient.builder().region(Region.of(properties.region()));
		if (StringUtils.hasText(properties.endpoint())) {
			builder.endpointOverride(URI.create(properties.endpoint()));
		}
		return builder.build();
	}

	@Bean
	StreamRelay streamRelay(RelayProperties relayProperties, DynamoDbProperties dynamoDbProperties,
			DynamoDbClient dynamoDb, DynamoDbStreamsClient streams, KafkaTemplate<String, Object> kafkaTemplate,
			@Value("${app.kafka.topics.link-events}") String topic, MeterRegistry meterRegistry) {
		return new StreamRelay(relayProperties, dynamoDbProperties.table(), dynamoDb, streams, kafkaTemplate, topic,
				meterRegistry);
	}

}
