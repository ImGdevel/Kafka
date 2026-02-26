package com.study.kafka.web;

import com.study.messaging.MessagePublisher;
import com.study.messaging.dto.MessagePayload;
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

	private final MessagePublisher messagePublisher;
	private final KafkaTemplate<Object, Object> kafkaTemplate;
	private final String topic;

	public MessageController(
		MessagePublisher messagePublisher,
		KafkaTemplate<Object, Object> kafkaTemplate,
		@Value("${app.kafka.topic}") String topic
	) {
		this.messagePublisher = messagePublisher;
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
	}

	@PostMapping
	public ResponseEntity<String> send(@RequestBody MessageRequest request) {
		messagePublisher.publish(request.message(), request.key());
		return ResponseEntity.accepted().body("sent");
	}

	@PostMapping("/transaction")
	public ResponseEntity<String> sendTransaction(@RequestBody TransactionRequest request) {
		kafkaTemplate.executeInTransaction(ops -> {
			for (String msg : request.messages()) {
				ops.send(topic, new MessagePayload(msg));
			}
			if (request.rollback()) {
				throw new RuntimeException("트랜잭션 롤백 시뮬레이션");
			}
			return null;
		});
		return ResponseEntity.accepted().body("transaction committed");
	}
}
