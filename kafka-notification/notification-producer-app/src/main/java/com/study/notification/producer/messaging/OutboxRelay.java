package com.study.notification.producer.messaging;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.producer.persistence.OutboxEventEntity;
import com.study.notification.producer.persistence.OutboxEventRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Outbox 이벤트를 Kafka에 릴레이하는 스케줄러.
 *
 * <p>Pillar 1 — Outbox Pattern: DB에 저장된 미발행 이벤트를 주기적으로 읽어 Kafka에 동기 발행하고,
 * 발행 완료된 레코드를 published=true로 표시한다.
 * Kafka 발행 실패 시 트랜잭션이 롤백되어 다음 주기에 재시도된다.
 */
@Slf4j
@Component
public class OutboxRelay {

	private final OutboxEventRepository outboxRepository;
	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final int batchSize;

	public OutboxRelay(
		OutboxEventRepository outboxRepository,
		KafkaTemplate<String, Object> kafkaTemplate,
		ObjectMapper objectMapper,
		@Value("${app.notification.outbox.batch-size:100}") int batchSize
	) {
		this.outboxRepository = outboxRepository;
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${app.notification.outbox.poll-interval-ms:1000}")
	@Transactional
	public void relay() {
		List<OutboxEventEntity> batch = outboxRepository.findUnpublishedWithLock(PageRequest.of(0, batchSize));
		if (batch.isEmpty()) {
			return;
		}

		log.debug("Outbox 릴레이 시작: 미발행 레코드 {}개", batch.size());

		for (OutboxEventEntity outbox : batch) {
			try {
				NotificationRequestedEvent event = objectMapper.readValue(
					outbox.getPayload(),
					NotificationRequestedEvent.class
				);
				kafkaTemplate.send(outbox.getTopic(), outbox.getNotificationId(), event).get();
				outbox.markPublished();
				log.info("Outbox 릴레이 완료: id={}, notificationId={}", outbox.getId(), outbox.getNotificationId());
			}
			catch (ExecutionException ex) {
				log.error("Outbox Kafka 발행 실패: id={}, notificationId={}", outbox.getId(), outbox.getNotificationId(), ex.getCause());
				throw new RuntimeException("Outbox Kafka 발행 실패: " + outbox.getNotificationId(), ex);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Outbox 릴레이 인터럽트: " + outbox.getNotificationId(), ex);
			}
			catch (Exception ex) {
				log.error("Outbox 직렬화 실패: id={}, notificationId={}", outbox.getId(), outbox.getNotificationId(), ex);
				throw new RuntimeException("Outbox 직렬화 실패: " + outbox.getNotificationId(), ex);
			}
		}
	}
}
