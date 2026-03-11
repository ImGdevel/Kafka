package com.study.kafka.service;

import com.study.kafka.web.MessageRequest;
import com.study.kafka.web.TransactionRequest;
import com.study.messaging.MessagePublisher;
import com.study.messaging.dto.MessagePayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka 실습 앱의 메시지 발행 흐름을 담당한다.
 * 컨트롤러는 HTTP 입출력만 맡고, 실제 발행 절차는 이 서비스가 처리한다.
 */
@Service
public class KafkaMessageService {

	private final MessagePublisher messagePublisher;
	private final KafkaTemplate<Object, Object> kafkaTemplate;
	private final String topic;

	public KafkaMessageService(
		MessagePublisher messagePublisher,
		KafkaTemplate<Object, Object> kafkaTemplate,
		@Value("${app.kafka.topic}") String topic
	) {
		this.messagePublisher = messagePublisher;
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
	}

	public void send(MessageRequest request) {
		messagePublisher.publish(request.message(), request.key());
	}

	public void sendInTransaction(TransactionRequest request) {
		kafkaTemplate.executeInTransaction(operations -> {
			sendAllMessages(operations, request);
			throwIfRollbackRequested(request);
			return null;
		});
	}

	private void sendAllMessages(KafkaOperations<Object, Object> operations, TransactionRequest request) {
		for (String message : request.messages()) {
			operations.send(topic, new MessagePayload(message));
		}
	}

	private void throwIfRollbackRequested(TransactionRequest request) {
		if (request.rollback()) {
			throw new RuntimeException("트랜잭션 롤백 시뮬레이션");
		}
	}
}
