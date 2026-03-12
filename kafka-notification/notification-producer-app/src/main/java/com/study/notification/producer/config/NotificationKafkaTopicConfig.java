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

	// 학습 포인트: 파티션 3개 → notification.requested 처리량 병렬화.
	//   순서 보장: notificationId를 key로 전송하므로 동일 알림은 항상 같은 파티션에 배정됨.
	private static final int PARTITION_COUNT = 3;
	// 학습 포인트: RF=3 → 브로커 1개 장애 시에도 notification 메시지 유실 없음
	private static final int REPLICA_COUNT = 3;
	private static final int MIN_ISR = 2;

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
			.config("min.insync.replicas", String.valueOf(MIN_ISR))
			.build();
	}
}
