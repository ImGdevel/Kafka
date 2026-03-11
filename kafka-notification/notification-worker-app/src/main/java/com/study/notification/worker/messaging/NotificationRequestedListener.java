package com.study.notification.worker.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.worker.service.NotificationWorkerService;

/**
 * notification.requested 토픽을 구독하는 Worker 진입점이다.
 */
@Component
@RequiredArgsConstructor
public class NotificationRequestedListener {

	private final NotificationWorkerService notificationWorkerService;

	@KafkaListener(
            topics = NotificationTopics.REQUESTED,
            groupId = "${spring.kafka.consumer.group-id}"
    )
	public void listen(NotificationRequestedEvent event) {
		notificationWorkerService.handle(event);
	}
}
