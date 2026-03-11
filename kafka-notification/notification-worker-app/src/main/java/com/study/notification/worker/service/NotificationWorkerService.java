package com.study.notification.worker.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.study.notification.contract.NotificationFailedEvent;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationSentEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.domain.NotificationChannel;
import com.study.notification.domain.NotificationContent;
import com.study.notification.domain.NotificationSendResult;
import com.study.notification.domain.NotificationSender;
import com.study.notification.domain.NotificationTarget;
import com.study.notification.domain.NotificationTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 요청 이벤트를 실제 전송 흐름으로 처리한다.
 */
@Slf4j
@Service
public class NotificationWorkerService {

	private final Map<NotificationChannel, NotificationSender> senderByChannel;
	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final int maxAttempts;
	private final long backoffMillis;

	public NotificationWorkerService(
		List<NotificationSender> senders,
		KafkaTemplate<String, Object> kafkaTemplate,
		@Value("${app.notification.retry.max-attempts:2}") int maxAttempts,
		@Value("${app.notification.retry.backoff-millis:1000}") long backoffMillis
	) {
		this.senderByChannel = senders.stream()
			.collect(Collectors.toMap(NotificationSender::channel, Function.identity()));
		this.kafkaTemplate = kafkaTemplate;
		this.maxAttempts = Math.max(1, maxAttempts);
		this.backoffMillis = Math.max(0L, backoffMillis);
	}

	public void handle(NotificationRequestedEvent event) {
		NotificationSender sender = getSender(event);

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				log.info("알림 전송 시도: notificationId={}, traceId={}, channel={}, attempt={}",
					event.notificationId(), event.traceId(), event.channel(), attempt);

				NotificationSendResult result = sender.send(
					new NotificationTarget(event.recipient()),
					new NotificationContent(event.subject(), event.body()),
					new NotificationTemplate(event.templateCode())
				);

				publishSentEvent(event, sender, result);
				return;
			}
			catch (Exception ex) {
				handleFailure(event, attempt, ex);
			}
		}
	}

	private NotificationSender getSender(NotificationRequestedEvent event) {
		NotificationSender sender = senderByChannel.get(event.channel());
		if (sender == null) {
			throw new IllegalStateException("채널에 맞는 Sender가 없습니다: " + event.channel());
		}
		return sender;
	}

	private void publishSentEvent(
		NotificationRequestedEvent event,
		NotificationSender sender,
		NotificationSendResult result
	) {
		NotificationSentEvent sentEvent = new NotificationSentEvent(
			event.notificationId(),
			event.traceId(),
			event.channel(),
			sender.getClass().getSimpleName()
		);
		kafkaTemplate.send(NotificationTopics.SENT, event.notificationId(), sentEvent);
		log.info("알림 전송 성공: notificationId={}, traceId={}, channel={}, provider={}, detail={}",
			event.notificationId(),
			event.traceId(),
			event.channel(),
			sender.getClass().getSimpleName(),
			result.detail());
	}

	private void handleFailure(NotificationRequestedEvent event, int attempt, Exception ex) {
		log.warn("알림 전송 실패: notificationId={}, traceId={}, channel={}, attempt={}, reason={}",
			event.notificationId(), event.traceId(), event.channel(), attempt, ex.getMessage());

		if (attempt >= maxAttempts) {
			NotificationFailedEvent failedEvent = new NotificationFailedEvent(
				event.notificationId(),
				event.traceId(),
				event.channel(),
				maxAttempts + "회 시도 후 실패: " + ex.getMessage()
			);
			kafkaTemplate.send(NotificationTopics.FAILED, event.notificationId(), failedEvent);
			log.warn("알림 실패 이벤트 발행 완료: notificationId={}, traceId={}, channel={}, reason={}",
				event.notificationId(), event.traceId(), event.channel(), failedEvent.reason());
			return;
		}

		sleepBackOff();
	}

	private void sleepBackOff() {
		try {
			Thread.sleep(backoffMillis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("재시도 대기 중 인터럽트가 발생했습니다.", ex);
		}
	}
}
