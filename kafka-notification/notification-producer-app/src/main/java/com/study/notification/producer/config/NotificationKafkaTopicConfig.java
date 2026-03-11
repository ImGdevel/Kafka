package com.study.notification.producer.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.study.notification.contract.NotificationTopics;

/**
 * kafka-notification E2E에서 사용하는 토픽을 생성한다.
 */
@Configuration
public class NotificationKafkaTopicConfig {

	private static final int PARTITION_COUNT = 1;
	private static final int REPLICA_COUNT = 1;

	@Bean
	public NewTopic notificationRequestedTopic() {
		return createTopic(NotificationTopics.REQUESTED);
	}

	@Bean
	public NewTopic notificationSentTopic() {
		return createTopic(NotificationTopics.SENT);
	}

	@Bean
	public NewTopic notificationFailedTopic() {
		return createTopic(NotificationTopics.FAILED);
	}

	@Bean
	public NewTopic notificationRequestedDltTopic() {
		return createTopic(NotificationTopics.REQUESTED_DLT);
	}

	private NewTopic createTopic(String topicName) {
		return TopicBuilder.name(topicName)
			.partitions(PARTITION_COUNT)
			.replicas(REPLICA_COUNT)
			.build();
	}
}
