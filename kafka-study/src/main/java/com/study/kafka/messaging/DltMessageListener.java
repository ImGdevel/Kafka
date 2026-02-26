package com.study.kafka.messaging;

import com.study.messaging.dto.MessagePayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * DLT에 쌓인 실패 레코드를 관찰하는 리스너 (재처리는 별도 로직에서 수행).
 * MANUAL_IMMEDIATE AckMode: 로그 출력 후 명시적으로 ack.acknowledge() 호출.
 */
@Slf4j
@Service
public class DltMessageListener {

	@KafkaListener(topics = "${app.kafka.dlt-topic}", groupId = "study-dlt-group")
	public void listenDlt(ConsumerRecord<String, MessagePayload> record, Acknowledgment ack) {
		log.warn("DLT 레코드 소비: value={}, key={}, dltPartition={}, dltOffset={}, 원본파티션={}, 원본오프셋={}",
			record.value().message(),
			record.key(),
			record.partition(),
			record.offset(),
			record.headers().lastHeader("kafka_dlt-original-partition") != null
				? new String(record.headers().lastHeader("kafka_dlt-original-partition").value())
				: "unknown",
			record.headers().lastHeader("kafka_dlt-original-offset") != null
				? new String(record.headers().lastHeader("kafka_dlt-original-offset").value())
				: "unknown"
		);
		ack.acknowledge();
	}
}
