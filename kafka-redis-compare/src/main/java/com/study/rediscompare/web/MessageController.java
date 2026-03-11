package com.study.rediscompare.web;

import com.study.rediscompare.service.RedisCompareMessageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

	private final RedisCompareMessageService messageService;

	public MessageController(RedisCompareMessageService messageService) {
		this.messageService = messageService;
	}

	@PostMapping
	public ResponseEntity<String> send(@RequestBody MessageRequest request) {
		messageService.send(request);
		return ResponseEntity.accepted().body("sent");
	}
}
