package com.study.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
	private KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	private TestListener testListener;

	@Test
	void sendsAndReceivesMessage() throws Exception {
		testListener.reset(1);
		kafkaTemplate.send("test-topic", "hello kafka");
		List<ConsumerRecord<String, String>> records = testListener.awaitRecords(1);
		assertThat(records).hasSize(1);
		assertThat(records.get(0).value()).isEqualTo("hello kafka");
	}

	@Test
	void sameKeyGoesToSamePartitionAndKeepsOrder() throws Exception {
		testListener.reset(2);
		kafkaTemplate.send("test-topic", "user-1", "m1");
		kafkaTemplate.send("test-topic", "user-1", "m2");

		List<ConsumerRecord<String, String>> records = testListener.awaitRecords(2);
		assertThat(records).hasSize(2);
		assertThat(records.get(0).key()).isEqualTo("user-1");
		assertThat(records.get(1).key()).isEqualTo("user-1");
		assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
		assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
	}

	@Test
	void failureIsSentToDltAfterRetry() throws Exception {
		testListener.reset(0);
		kafkaTemplate.send("test-topic", "will fail");

		List<ConsumerRecord<String, String>> dltRecords = testListener.awaitDltRecords(1);
		assertThat(dltRecords).hasSize(1);
		assertThat(dltRecords.get(0).value()).isEqualTo("will fail");
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
		private final List<ConsumerRecord<String, String>> records = new ArrayList<>();
		private final List<ConsumerRecord<String, String>> dltRecords = new ArrayList<>();

		@KafkaListener(topics = "${app.kafka.topic}", groupId = "test-group")
		synchronized void listen(ConsumerRecord<String, String> record) {
			records.add(record);
			latch.get().countDown();
		}

		@KafkaListener(topics = "${app.kafka.dlt-topic}", groupId = "test-group-dlt")
		synchronized void listenDlt(ConsumerRecord<String, String> record) {
			dltRecords.add(record);
			dltLatch.get().countDown();
		}

		synchronized void reset(int expectedCount) {
			records.clear();
			dltRecords.clear();
			latch.set(new CountDownLatch(expectedCount));
			dltLatch.set(new CountDownLatch(0));
		}

		List<ConsumerRecord<String, String>> awaitRecords(int expectedCount) throws InterruptedException {
			boolean completed = latch.get().await(10, TimeUnit.SECONDS);
			if (!completed) {
				return List.of();
			}
			synchronized (this) {
				return List.copyOf(records);
			}
		}

		List<ConsumerRecord<String, String>> awaitDltRecords(int expectedCount) throws InterruptedException {
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
