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

	@Bean
	public NewTopic studyTopic() {
		return TopicBuilder.name(topic)
			.partitions(1)
			.replicas(1)
			.build();
	}
}
