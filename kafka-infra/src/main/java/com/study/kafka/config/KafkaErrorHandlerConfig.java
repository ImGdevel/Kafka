package com.study.kafka.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlerConfig {

	@Bean
	CommonErrorHandler commonErrorHandler(
		KafkaTemplate<String, String> kafkaTemplate,
		@Value("${app.kafka.dlt-topic}") String dltTopic
	) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
			kafkaTemplate,
			(record, ex) -> new TopicPartition(dltTopic, record.partition())
		);
		// 1회 재시도 후 DLT 전송 (처음 실패 + 재시도 1회 = 총 2회 처리)
		return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 1));
	}
}

