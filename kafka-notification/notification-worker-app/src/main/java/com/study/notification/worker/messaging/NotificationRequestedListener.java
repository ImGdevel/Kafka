package com.study.notification.worker.messaging;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.worker.service.NotificationWorkerService;

/**
 * notification.requested 토픽을 구독하는 Worker 진입점이다.
 *
 * <p>Pillar 2 — MANUAL ack: ConsumerRecord와 Acknowledgment를 함께 받아
 * 처리 완료 후 명시적으로 오프셋을 커밋한다. Kafka TX와 결합 시
 * sendOffsetsToTransaction()이 자동 호출된다.
 */
@Component
@RequiredArgsConstructor
public class NotificationRequestedListener {

	private final NotificationWorkerService notificationWorkerService;

	@KafkaListener(
		topics = NotificationTopics.REQUESTED,
		groupId = "${spring.kafka.consumer.group-id}"
	)
	public void listen(
		ConsumerRecord<String, NotificationRequestedEvent> record,
		Acknowledgment ack
	) {
		notificationWorkerService.handle(record.value(), ack);
	}
}
