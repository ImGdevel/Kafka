package com.study.kafka.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DltMessageListener {

	@KafkaListener(topics = "${app.kafka.dlt-topic}", groupId = "study-dlt-group")
	public void listenDlt(ConsumerRecord<String, String> record) {
		log.warn(
			"Consumed DLT message: {} (key={}, partition={}, offset={}, origPartition={}, origOffset={})",
			record.value(),
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
	}
}
