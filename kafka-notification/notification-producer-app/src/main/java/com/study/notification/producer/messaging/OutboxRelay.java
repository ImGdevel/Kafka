package com.study.notification.producer.messaging;

import java.util.List;

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
 * <p>Pillar 1 (개선) — Outbox Pattern + Kafka TX:
 * DB에 저장된 미발행 이벤트를 주기적으로 읽어 Kafka 트랜잭션 내에서 발행하고,
 * 발행 완료된 레코드를 published=true로 표시한다.
 *
 * <p>Kafka TX(executeInTransaction) 적용 효과:
 * <ul>
 *   <li>send + markPublished를 Kafka TX 범위 내에서 실행한다.</li>
 *   <li>TX 커밋 전 메시지는 read_committed 소비자에게 보이지 않으므로,
 *       "send 성공 + markPublished 실패 → 중복 발행" 시나리오가 차단된다.</li>
 *   <li>TX 실패 시 JPA @Transactional도 롤백되어 다음 주기에 재시도된다.</li>
 * </ul>
 *
 * <p>주의: markPublished()는 Kafka TX 커밋 이후 JPA TX 커밋 순서로 영속화된다.
 * Kafka TX 커밋 후 JPA TX 실패 시 아웃박스가 미발행으로 남아 재발행될 수 있으며,
 * Worker의 Pillar 3(멱등 소비)이 최종 방어선으로 동작한다.
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
	@Transactional("transactionManager")
	public void relay() {
		List<OutboxEventEntity> batch = outboxRepository.findUnpublishedWithLock(PageRequest.of(0, batchSize));
		if (batch.isEmpty()) {
			return;
		}

		log.debug("Outbox 릴레이 시작: 미발행 레코드 {}개", batch.size());

		for (OutboxEventEntity outbox : batch) {
			try {
				// Kafka TX 내에서 send + markPublished를 원자적으로 실행한다.
				// TX 커밋 전 메시지는 read_committed 소비자에게 보이지 않으며,
				// TX 실패 시 outbox 레코드는 미발행 상태로 유지되어 다음 주기에 재시도된다.
				kafkaTemplate.executeInTransaction(ops -> {
					try {
						NotificationRequestedEvent event = objectMapper.readValue(
							outbox.getPayload(),
							NotificationRequestedEvent.class
						);
						ops.send(outbox.getTopic(), outbox.getNotificationId(), event);
						outbox.markPublished();
						return null;
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				});
				log.info("Outbox 릴레이 완료: id={}, notificationId={}", outbox.getId(), outbox.getNotificationId());
			} catch (Exception ex) {
				log.error("Outbox 릴레이 실패: id={}, notificationId={}", outbox.getId(), outbox.getNotificationId(), ex);
				throw new RuntimeException("Outbox 릴레이 실패: " + outbox.getNotificationId(), ex);
			}
		}
	}
}
