package com.study.kafka.messaging;

import com.study.messaging.dto.MessagePayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 학습용 리스너. payload에 "fail"이 포함되면 예외를 던져 DLT 전송을 확인한다.
 * MANUAL_IMMEDIATE AckMode: 처리 성공 후 명시적으로 ack.acknowledge() 호출.
 */
@Slf4j
@Service
public class MessageListener {

	private static final String FAILURE_TRIGGER_KEYWORD = "fail";

	@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
	public void listen(ConsumerRecord<String, MessagePayload> record, Acknowledgment ack) {
		String message = record.value().message();
		throwIfMarkedToFail(message);
		logConsumedRecord(record, message);
		ack.acknowledge();
	}

	private void throwIfMarkedToFail(String message) {
		if (message != null && message.contains(FAILURE_TRIGGER_KEYWORD)) {
			throw new IllegalStateException("실패를 시뮬레이션한 메시지: " + message);
		}
	}

	private void logConsumedRecord(ConsumerRecord<String, MessagePayload> record, String message) {
		log.info("메시지 소비 완료: value={}, key={}, partition={}, offset={}",
			message, record.key(), record.partition(), record.offset());
	}
}
