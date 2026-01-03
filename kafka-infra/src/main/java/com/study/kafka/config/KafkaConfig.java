package com.study.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

	@Value("${app.kafka.topic}")
	private String topic;

	@Value("${app.kafka.dlt-topic}")
	private String dltTopic;

	@Bean
	public NewTopic studyTopic() {
		return TopicBuilder.name(topic)
			.partitions(3)
			.replicas(1)
			.build();
	}

	@Bean
	public NewTopic studyDltTopic() {
		return TopicBuilder.name(dltTopic)
			.partitions(3)
			.replicas(1)
			.build();
	}
}
