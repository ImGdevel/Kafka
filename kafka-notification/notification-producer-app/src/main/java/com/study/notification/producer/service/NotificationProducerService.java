package com.study.notification.producer.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.producer.config.NotificationProducerProperties;
import com.study.notification.producer.persistence.NotificationRequestEntity;
import com.study.notification.producer.persistence.NotificationRequestRepository;
import com.study.notification.producer.persistence.OutboxEventEntity;
import com.study.notification.producer.persistence.OutboxEventRepository;
import com.study.notification.producer.web.NotificationAcceptedResponse;
import com.study.notification.producer.web.NotificationRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 요청을 DB 트랜잭션으로 저장하고 Outbox에 적재한다.
 *
 * <p>Pillar 1 — Outbox Pattern: HTTP 요청 처리와 Kafka 발행을 원자적으로 분리한다.
 * kafkaTemplate.send()를 직접 호출하는 대신 outbox_events 테이블에 저장하여,
 * OutboxRelay가 비동기로 Kafka에 발행하도록 위임한다.
 */
@Slf4j
@Service
public class NotificationProducerService {

	private static final String ACCEPTED_STATUS = "ACCEPTED";

	private final NotificationRequestRepository requestRepository;
	private final OutboxEventRepository outboxRepository;
	private final ObjectMapper objectMapper;
	private final NotificationProducerProperties properties;

	public NotificationProducerService(
		NotificationRequestRepository requestRepository,
		OutboxEventRepository outboxRepository,
		ObjectMapper objectMapper,
		NotificationProducerProperties properties
	) {
		this.requestRepository = requestRepository;
		this.outboxRepository = outboxRepository;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	/**
	 * 알림 요청을 접수하고 DB 트랜잭션 안에서 notification_requests + outbox_events를 저장한다.
	 *
	 * <p>트랜잭션이 커밋된 이후 OutboxRelay가 outbox_events를 읽어 Kafka에 발행한다.
	 */
	@Transactional("transactionManager")
	public NotificationAcceptedResponse accept(NotificationRequest request) {
		validateAllowedChannel(request);

		String notificationId = UUID.randomUUID().toString();
		String traceId = UUID.randomUUID().toString();

		NotificationRequestedEvent event = new NotificationRequestedEvent(
			notificationId,
			traceId,
			request.channel(),
			request.recipient(),
			request.subject(),
			request.body(),
			request.templateCode()
		);

		// notification_requests 저장
		requestRepository.save(NotificationRequestEntity.accepted(event));

		// outbox_events 저장 (OutboxRelay가 Kafka 발행)
		String payload = serializeEvent(event);
		outboxRepository.save(OutboxEventEntity.of(notificationId, NotificationTopics.REQUESTED, payload));

		log.info("알림 요청 접수 완료 (Outbox 적재): notificationId={}, traceId={}, channel={}, recipient={}",
			notificationId, traceId, request.channel(), request.recipient());

		return new NotificationAcceptedResponse(notificationId, traceId, ACCEPTED_STATUS);
	}

	private void validateAllowedChannel(NotificationRequest request) {
		if (!properties.getAllowedChannels().contains(request.channel())) {
			throw new ResponseStatusException(
				HttpStatus.BAD_REQUEST,
				"허용되지 않은 알림 채널입니다: " + request.channel()
			);
		}
	}

	private String serializeEvent(NotificationRequestedEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Outbox 이벤트 직렬화 실패: " + event.notificationId(), ex);
		}
	}
}
