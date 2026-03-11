package com.study.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "app.mq.type", havingValue = "kafka", matchIfMissing = true)
public class KafkaConfig {

	private static final int PARTITION_COUNT = 3;
	private static final int REPLICA_COUNT = 1;

	@Value("${app.kafka.topic}")
	private String topic;

	@Value("${app.kafka.dlt-topic}")
	private String dltTopic;

	@Bean
	public NewTopic studyTopic() {
		return createTopic(topic);
	}

	@Bean
	public NewTopic studyDltTopic() {
		return createTopic(dltTopic);
	}

	private NewTopic createTopic(String topicName) {
		return TopicBuilder.name(topicName)
			.partitions(PARTITION_COUNT)
			.replicas(REPLICA_COUNT)
			.build();
	}
}
