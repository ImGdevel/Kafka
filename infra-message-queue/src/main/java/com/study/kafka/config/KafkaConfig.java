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
	// 학습 포인트: RF=3 → 브로커 1개 장애 시에도 읽기/쓰기 가능
	private static final int REPLICA_COUNT = 3;
	// 학습 포인트: min.insync.replicas=2 → acks=all 프로듀서는 ISR에 2개 이상 복제 완료 후 커밋 응답.
	//   ISR이 1개만 남으면 NotEnoughReplicasException 발생 → 데이터 유실 대신 에러 반환.
	private static final int MIN_ISR = 2;

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
			.config("min.insync.replicas", String.valueOf(MIN_ISR))
			.build();
	}
}
