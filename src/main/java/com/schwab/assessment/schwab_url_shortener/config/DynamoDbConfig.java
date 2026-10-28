package com.schwab.assessment.schwab_url_shortener.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB clients. Credentials come from the SDK's default provider chain: environment variables locally
 * (dummy values for DynamoDB Local), the pod's IAM role in production.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

	@Bean(destroyMethod = "close")
	DynamoDbClient dynamoDbClient(DynamoDbProperties properties) {
		var builder = DynamoDbClient.builder().region(Region.of(properties.region()));
		if (StringUtils.hasText(properties.endpoint())) {
			builder.endpointOverride(URI.create(properties.endpoint()));
		}
		return builder.build();
	}

	@Bean
	DynamoDbHealthIndicator dynamoDbHealthIndicator(DynamoDbClient dynamoDbClient, DynamoDbProperties properties) {
		return new DynamoDbHealthIndicator(dynamoDbClient, properties.table());
	}

}
