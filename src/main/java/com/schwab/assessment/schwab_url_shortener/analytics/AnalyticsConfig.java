package com.schwab.assessment.schwab_url_shortener.analytics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties.class)
class AnalyticsConfig {

	static final String CONTAINER_FACTORY = "clickProcessorContainerFactory";

	/**
	 * Batch listener that retries a failed batch with exponential backoff (1 s up to 1 min) indefinitely instead of
	 * the default 10 quick retries followed by skipping, which would silently drop clicks during a ClickHouse outage.
	 */
	@Bean(CONTAINER_FACTORY)
	ConcurrentKafkaListenerContainerFactory<Object, Object> clickProcessorContainerFactory(
			ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory) {
		var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
		configurer.configure(factory, consumerFactory);
		factory.setBatchListener(true);
		factory.setCommonErrorHandler(new DefaultErrorHandler(retryBackOff()));
		return factory;
	}

	/** 1 s, doubling up to 1 min, with no limit on attempts or elapsed time: a failed batch is never skipped. */
	static BackOff retryBackOff() {
		var backOff = new ExponentialBackOff(1_000, 2);
		backOff.setMaxInterval(60_000);
		return backOff;
	}

	@Bean
	ClickProcessor clickProcessor(AnalyticsProperties properties, JdbcTemplate jdbc, MeterRegistry meterRegistry) {
		var enricher = new ClickEnricher(new VisitorHasher(properties.visitorSaltSecret()), properties.region());
		return new ClickProcessor(enricher, new ClickHouseClickWriter(jdbc), meterRegistry);
	}

}
