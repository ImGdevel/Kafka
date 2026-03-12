package com.study.notification.worker.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.study.notification.worker.persistence.ProcessedNotificationEntity;
import com.study.notification.worker.persistence.ProcessedNotificationRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 요청 이벤트를 EOS 보장 흐름으로 처리한다.
 *
 * <p>5-Pillar EOS 구현:
 * <ul>
 *   <li>Pillar 2 — Kafka TX: transaction-id-prefix 설정으로 result 이벤트 발행 + 오프셋 커밋을 원자적으로 처리</li>
 *   <li>Pillar 3 — 멱등 소비: processed_notifications 테이블로 중복 redelivery 스킵</li>
 *   <li>Pillar 4 — 멱등 전송: SandboxSender의 sentIds dedup</li>
 * </ul>
 *
 * <p>트랜잭션 커밋 순서: JPA 트랜잭션(DB 커밋) → Kafka TX 커밋(result 이벤트 + 오프셋).
 * Kafka TX 커밋 실패 시 DB 레코드가 이미 존재하므로 재전달 때 Pillar 3 게이트에서 스킵한다.
 */
@Slf4j
@Service
public class NotificationWorkerService {

	private final Map<NotificationChannel, NotificationSender> senderByChannel;
	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final ProcessedNotificationRepository processedRepository;
	private final int maxAttempts;
	private final long backoffMillis;

	public NotificationWorkerService(
		List<NotificationSender> senders,
		KafkaTemplate<String, Object> kafkaTemplate,
		ProcessedNotificationRepository processedRepository,
		@Value("${app.notification.retry.max-attempts:2}") int maxAttempts,
		@Value("${app.notification.retry.backoff-millis:1000}") long backoffMillis
	) {
		this.senderByChannel = senders.stream()
			.collect(Collectors.toMap(NotificationSender::channel, Function.identity()));
		this.kafkaTemplate = kafkaTemplate;
		this.processedRepository = processedRepository;
		this.maxAttempts = Math.max(1, maxAttempts);
		this.backoffMillis = Math.max(0L, backoffMillis);
	}

	/**
	 * 알림 요청을 처리한다.
	 *
	 * <p>@Transactional은 JPA 트랜잭션을 관리한다. Kafka 트랜잭션은 KafkaTransactionManager가
	 * 리스너 컨테이너 수준에서 별도로 관리하며, ack.acknowledge() 호출 시
	 * sendOffsetsToTransaction()을 통해 소비 오프셋을 Kafka TX에 포함시킨 뒤 커밋한다.
	 */
	@Transactional("transactionManager")
	public void handle(NotificationRequestedEvent event, Acknowledgment ack) {

		// Pillar 3: 멱등성 게이트 — 이미 처리된 메시지는 스킵
		if (processedRepository.existsByNotificationId(event.notificationId())) {
			log.warn("중복 메시지 스킵 (이미 처리됨): notificationId={}", event.notificationId());
			ack.acknowledge();
			return;
		}

		NotificationSender sender = getSender(event);
		NotificationSendResult result = null;
		String failureReason = null;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				log.info("알림 전송 시도: notificationId={}, channel={}, attempt={}",
					event.notificationId(), event.channel(), attempt);

				// Pillar 4: notificationId를 전달하여 Sandbox 멱등 전송 dedup 적용
				result = sender.send(
					new NotificationTarget(event.recipient()),
					new NotificationContent(event.subject(), event.body()),
					new NotificationTemplate(event.templateCode(), event.notificationId())
				);
				break;
			}
			catch (Exception ex) {
				failureReason = ex.getMessage();
				log.warn("알림 전송 실패: notificationId={}, channel={}, attempt={}, reason={}",
					event.notificationId(), event.channel(), attempt, failureReason);
				if (attempt < maxAttempts) {
					sleepBackOff();
				}
			}
		}

		// Pillar 2: Kafka TX — result 이벤트 발행 (KafkaTransactionManager가 TX를 관리)
		if (result != null) {
			kafkaTemplate.send(NotificationTopics.SENT, event.notificationId(),
				new NotificationSentEvent(
					event.notificationId(),
					event.traceId(),
					event.channel(),
					sender.getClass().getSimpleName()
				)
			);
			processedRepository.save(
				ProcessedNotificationEntity.sent(event.notificationId(), sender.getClass().getSimpleName())
			);
			log.info("알림 전송 성공: notificationId={}, channel={}, provider={}",
				event.notificationId(), event.channel(), sender.getClass().getSimpleName());
		}
		else {
			String reason = maxAttempts + "회 시도 후 실패: " + failureReason;
			kafkaTemplate.send(NotificationTopics.FAILED, event.notificationId(),
				new NotificationFailedEvent(
					event.notificationId(),
					event.traceId(),
					event.channel(),
					reason
				)
			);
			processedRepository.save(
				ProcessedNotificationEntity.failed(event.notificationId(), reason)
			);
			log.warn("알림 전송 실패 이벤트 발행: notificationId={}, channel={}, reason={}",
				event.notificationId(), event.channel(), reason);
		}

		// MANUAL ack: Spring Kafka가 sendOffsetsToTransaction() 호출 후 Kafka TX 커밋
		ack.acknowledge();
	}

	private NotificationSender getSender(NotificationRequestedEvent event) {
		NotificationSender sender = senderByChannel.get(event.channel());
		if (sender == null) {
			throw new IllegalStateException("채널에 맞는 Sender가 없습니다: " + event.channel());
		}
		return sender;
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
