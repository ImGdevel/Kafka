package com.study.kafka.infra.kafka;

import com.study.messaging.MessagePublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.mq.type", havingValue = "kafka", matchIfMissing = true)
public class KafkaMessagePublisher implements MessagePublisher {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final String topic;

	public KafkaMessagePublisher(
		KafkaTemplate<String, String> kafkaTemplate,
		@Value("${app.kafka.topic}") String topic
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
	}

	@Override
	public void publish(String message, String key) {
		if (key == null || key.isBlank()) {
			kafkaTemplate.send(topic, message);
		}
		else {
			kafkaTemplate.send(topic, key, message);
		}
	}
}
