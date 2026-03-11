package com.study.notification.producer.web;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.study.notification.producer.service.NotificationProducerService;
import com.study.notification.producer.service.NotificationQueryService;

/**
 * 외부 알림 요청을 받는 HTTP 진입점이다.
 */
@Validated
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationProducerService notificationProducerService;
	private final NotificationQueryService notificationQueryService;

	public NotificationController(
		NotificationProducerService notificationProducerService,
		NotificationQueryService notificationQueryService
	) {
		this.notificationProducerService = notificationProducerService;
		this.notificationQueryService = notificationQueryService;
	}

	@PostMapping
	public ResponseEntity<NotificationAcceptedResponse> send(
		@RequestBody @jakarta.validation.Valid NotificationRequest request
	) {
		NotificationAcceptedResponse response = notificationProducerService.accept(request);
		return ResponseEntity.accepted().body(response);
	}

	@GetMapping("/{notificationId}")
	public ResponseEntity<NotificationStatusResponse> get(@PathVariable String notificationId) {
		return ResponseEntity.ok(notificationQueryService.get(notificationId));
	}
}
