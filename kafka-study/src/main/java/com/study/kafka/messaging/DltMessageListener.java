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

	private static final String ORIGINAL_PARTITION_HEADER = "kafka_dlt-original-partition";
	private static final String ORIGINAL_OFFSET_HEADER = "kafka_dlt-original-offset";

	@KafkaListener(topics = "${app.kafka.dlt-topic}", groupId = "study-dlt-group")
	public void listenDlt(ConsumerRecord<String, MessagePayload> record, Acknowledgment ack) {
		String originalPartition = readHeader(record, ORIGINAL_PARTITION_HEADER);
		String originalOffset = readHeader(record, ORIGINAL_OFFSET_HEADER);
		log.warn("DLT 레코드 소비: value={}, key={}, dltPartition={}, dltOffset={}, 원본파티션={}, 원본오프셋={}",
			record.value().message(),
			record.key(),
			record.partition(),
			record.offset(),
			originalPartition,
			originalOffset
		);
		ack.acknowledge();
	}

	private String readHeader(ConsumerRecord<String, MessagePayload> record, String headerName) {
		if (record.headers().lastHeader(headerName) == null) {
			return "unknown";
		}
		return new String(record.headers().lastHeader(headerName).value());
	}
}
