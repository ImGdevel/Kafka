package com.study.kafka.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final String topic;

	public MessageController(
		KafkaTemplate<String, String> kafkaTemplate,
		@Value("${app.kafka.topic}") String topic
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
	}

	@PostMapping
	public ResponseEntity<String> send(@RequestBody MessageRequest request) {
		if (request.key() == null || request.key().isBlank()) {
			kafkaTemplate.send(topic, request.message());
		}
		else {
			kafkaTemplate.send(topic, request.key(), request.message());
		}
		return ResponseEntity.accepted().body("sent");
	}
}
