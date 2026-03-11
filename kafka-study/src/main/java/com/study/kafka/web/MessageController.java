package com.study.kafka.web;

import com.study.kafka.service.KafkaMessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

	private final KafkaMessageService kafkaMessageService;

	public MessageController(KafkaMessageService kafkaMessageService) {
		this.kafkaMessageService = kafkaMessageService;
	}

	@PostMapping
	public ResponseEntity<String> send(@RequestBody MessageRequest request) {
		kafkaMessageService.send(request);
		return ResponseEntity.accepted().body("sent");
	}

	@PostMapping("/transaction")
	public ResponseEntity<String> sendTransaction(@RequestBody TransactionRequest request) {
		kafkaMessageService.sendInTransaction(request);
		return ResponseEntity.accepted().body("transaction committed");
	}
}
