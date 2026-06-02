package com.study.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.study.messaging.dto.MessagePayload;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(
	classes = {KafkaApplication.class, KafkaMessagingIntegrationTest.TestConfig.class},
	properties = {
		"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"app.kafka.topic=test-topic",
		"app.kafka.dlt-topic=test-topic-dlt"
	}
)
@EmbeddedKafka(partitions = 3, topics = {"test-topic", "test-topic-dlt"})
class KafkaMessagingIntegrationTest {

	@Autowired
	private KafkaTemplate<Object, Object> kafkaTemplate;

	@Autowired
	private TestListener testListener;

	@Test
	void sendsAndReceivesMessage() throws Exception {
		testListener.reset(1);
		kafkaTemplate.executeInTransaction(ops -> ops.send("test-topic", new MessagePayload("hello kafka")));
		List<ConsumerRecord<String, MessagePayload>> records = testListener.awaitRecords(1);
		assertThat(records).hasSize(1);
		assertThat(records.get(0).value().message()).isEqualTo("hello kafka");
	}

	@Test
	void sameKeyGoesToSamePartitionAndKeepsOrder() throws Exception {
		testListener.reset(2);
		kafkaTemplate.executeInTransaction(ops -> ops.send("test-topic", "user-1", new MessagePayload("m1")));
		kafkaTemplate.executeInTransaction(ops -> ops.send("test-topic", "user-1", new MessagePayload("m2")));

		List<ConsumerRecord<String, MessagePayload>> records = testListener.awaitRecords(2);
		assertThat(records).hasSize(2);
		assertThat(records.get(0).key()).isEqualTo("user-1");
		assertThat(records.get(1).key()).isEqualTo("user-1");
		assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
		assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
	}

	@Test
	void failureIsSentToDltAfterRetry() throws Exception {
		testListener.reset(0);
		kafkaTemplate.executeInTransaction(ops -> ops.send("test-topic", new MessagePayload("will fail")));

		List<ConsumerRecord<String, MessagePayload>> dltRecords = testListener.awaitDltRecords(1);
		assertThat(dltRecords).hasSize(1);
		assertThat(dltRecords.get(0).value().message()).isEqualTo("will fail");
		assertThat(dltRecords.get(0).topic()).isEqualTo("test-topic-dlt");
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		TestListener testListener() {
			return new TestListener();
		}
	}

	static class TestListener {

		private final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(0));
		private final AtomicReference<CountDownLatch> dltLatch = new AtomicReference<>(new CountDownLatch(0));
		private final List<ConsumerRecord<String, MessagePayload>> records = new ArrayList<>();
		private final List<ConsumerRecord<String, MessagePayload>> dltRecords = new ArrayList<>();

		@KafkaListener(topics = "${app.kafka.topic}", groupId = "test-group")
		synchronized void listen(ConsumerRecord<String, MessagePayload> record) {
			records.add(record);
			latch.get().countDown();
		}

		@KafkaListener(topics = "${app.kafka.dlt-topic}", groupId = "test-group-dlt")
		synchronized void listenDlt(ConsumerRecord<String, MessagePayload> record) {
			dltRecords.add(record);
			dltLatch.get().countDown();
		}

		synchronized void reset(int expectedCount) {
			records.clear();
			dltRecords.clear();
			latch.set(new CountDownLatch(expectedCount));
			dltLatch.set(new CountDownLatch(0));
		}

		List<ConsumerRecord<String, MessagePayload>> awaitRecords(int expectedCount) throws InterruptedException {
			boolean completed = latch.get().await(10, TimeUnit.SECONDS);
			if (!completed) {
				return List.of();
			}
			synchronized (this) {
				return List.copyOf(records);
			}
		}

		List<ConsumerRecord<String, MessagePayload>> awaitDltRecords(int expectedCount) throws InterruptedException {
			dltLatch.set(new CountDownLatch(expectedCount));
			boolean completed = dltLatch.get().await(10, TimeUnit.SECONDS);
			if (!completed) {
				return List.of();
			}
			synchronized (this) {
				return List.copyOf(dltRecords);
			}
		}
	}
}
