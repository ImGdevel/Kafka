package com.study.kafka.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class MessageListener {

	private static final Logger log = LoggerFactory.getLogger(MessageListener.class);

	@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
	public void listen(ConsumerRecord<String, String> record) {
		log.info(
			"Consumed message: {} (key={}, partition={}, offset={})",
			record.value(),
			record.key(),
			record.partition(),
			record.offset()
		);
	}
}
