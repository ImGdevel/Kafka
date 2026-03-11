package com.study.notification.producer.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.study.notification.contract.NotificationFailedEvent;
import com.study.notification.contract.NotificationSentEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.producer.service.NotificationQueryService;

import lombok.extern.slf4j.Slf4j;

/**
 * Worker 결과 이벤트를 소비해 조회 상태를 갱신한다.
 */
@Slf4j
@Component
public class NotificationResultListener {

	private final NotificationQueryService notificationQueryService;

	public NotificationResultListener(NotificationQueryService notificationQueryService) {
		this.notificationQueryService = notificationQueryService;
	}

	@KafkaListener(
		topics = NotificationTopics.SENT,
		groupId = "${app.notification.query.consumer-group:notification-producer-query-group}"
	)
	public void listenSent(NotificationSentEvent event) {
		notificationQueryService.markSent(event);
		log.info("알림 조회 상태 갱신 완료: notificationId={}, status=SENT, provider={}",
			event.notificationId(), event.provider());
	}

	@KafkaListener(
		topics = NotificationTopics.FAILED,
		groupId = "${app.notification.query.consumer-group:notification-producer-query-group}"
	)
	public void listenFailed(NotificationFailedEvent event) {
		notificationQueryService.markFailed(event);
		log.info("알림 조회 상태 갱신 완료: notificationId={}, status=FAILED, reason={}",
			event.notificationId(), event.reason());
	}
}
