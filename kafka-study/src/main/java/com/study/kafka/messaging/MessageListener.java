package com.study.kafka.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MessageListener {

	@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
	public void listen(ConsumerRecord<String, String> record) {
		if (record.value() != null && record.value().contains("fail")) {
			throw new IllegalStateException("Simulated failure for message: " + record.value());
		}
		log.info(
			"Consumed message: {} (key={}, partition={}, offset={})",
			record.value(),
			record.key(),
			record.partition(),
			record.offset()
		);
	}
}
