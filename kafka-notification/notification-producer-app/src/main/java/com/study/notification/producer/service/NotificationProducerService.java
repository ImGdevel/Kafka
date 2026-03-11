package com.study.notification.producer.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.study.notification.contract.NotificationHeaderNames;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.producer.config.NotificationProducerProperties;
import com.study.notification.producer.web.NotificationAcceptedResponse;
import com.study.notification.producer.web.NotificationRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * 알림 요청을 Kafka에 적재한다.
 */
@Slf4j
@Service
public class NotificationProducerService {

	private static final String ACCEPTED_STATUS = "ACCEPTED";

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final NotificationProducerProperties properties;
	private final NotificationQueryService notificationQueryService;

	public NotificationProducerService(
		KafkaTemplate<String, Object> kafkaTemplate,
		NotificationProducerProperties properties,
		NotificationQueryService notificationQueryService
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
		this.notificationQueryService = notificationQueryService;
	}

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

		ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(
			NotificationTopics.REQUESTED,
			notificationId,
			event
		);
		addHeaders(producerRecord, event);
		notificationQueryService.registerAccepted(event);
		kafkaTemplate.send(producerRecord);

		log.info("알림 요청 적재 완료: notificationId={}, traceId={}, channel={}, recipient={}",
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

	private void addHeaders(ProducerRecord<String, Object> producerRecord, NotificationRequestedEvent event) {
		producerRecord.headers().add(
			NotificationHeaderNames.TRACE_ID,
			event.traceId().getBytes(StandardCharsets.UTF_8)
		);
		producerRecord.headers().add(
			NotificationHeaderNames.NOTIFICATION_ID,
			event.notificationId().getBytes(StandardCharsets.UTF_8)
		);
		producerRecord.headers().add(
			NotificationHeaderNames.CHANNEL,
			event.channel().name().getBytes(StandardCharsets.UTF_8)
		);
	}
}
