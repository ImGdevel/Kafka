package com.study.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.study.notification.contract.NotificationFailedEvent;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationSentEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.domain.NotificationChannel;
import com.study.notification.provider.sandbox.SandboxEmailSender;

@SpringBootTest(
	classes = {NotificationWorkerApplication.class, NotificationWorkerApplicationTests.TestConfig.class},
	properties = {
		"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"app.notification.retry.backoff-millis=0"
	}
)
@EmbeddedKafka(partitions = 1, topics = {
	NotificationTopics.REQUESTED,
	NotificationTopics.SENT,
	NotificationTopics.FAILED
})
class NotificationWorkerApplicationTests {

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Autowired
	private NotificationResultCapture notificationResultCapture;

	@MockitoSpyBean
	private SandboxEmailSender sandboxEmailSender;

	@BeforeEach
	void setUp() {
		notificationResultCapture.reset();
		Mockito.clearInvocations(sandboxEmailSender);
	}

	@Test
	@DisplayName("샌드박스 전송이 성공하면 notification.sent 이벤트를 발행한다.")
	void successEventIsPublishedWhenSenderSucceeds() throws Exception {
		kafkaTemplate.send(NotificationTopics.REQUESTED, "notification-1", new NotificationRequestedEvent(
			"notification-1",
			"trace-1",
			NotificationChannel.EMAIL,
			"user@example.com",
			"welcome",
			"hello",
			"WELCOME"
		));

		NotificationSentEvent sentEvent = notificationResultCapture.awaitSent();
		assertThat(sentEvent).isNotNull();
		assertThat(sentEvent.notificationId()).isEqualTo("notification-1");
		assertThat(sentEvent.traceId()).isEqualTo("trace-1");
		assertThat(sentEvent.channel()).isEqualTo(NotificationChannel.EMAIL);
		assertThat(sentEvent.provider()).isEqualTo("SandboxEmailSender");
	}

	@Test
	@DisplayName("FAIL_ALWAYS 요청은 1회 재시도 후 notification.failed 이벤트를 발행한다.")
	void failureEventIsPublishedAfterSingleRetry() throws Exception {
		kafkaTemplate.send(NotificationTopics.REQUESTED, "notification-2", new NotificationRequestedEvent(
			"notification-2",
			"trace-2",
			NotificationChannel.EMAIL,
			"user@example.com",
			"welcome",
			"hello",
			"FAIL_ALWAYS"
		));

		NotificationFailedEvent failedEvent = notificationResultCapture.awaitFailed();
		assertThat(failedEvent).isNotNull();
		assertThat(failedEvent.notificationId()).isEqualTo("notification-2");
		assertThat(failedEvent.traceId()).isEqualTo("trace-2");
		assertThat(failedEvent.channel()).isEqualTo(NotificationChannel.EMAIL);
		assertThat(failedEvent.reason()).contains("2회 시도 후 실패");
		verify(sandboxEmailSender, times(2)).send(Mockito.any(), Mockito.any(), Mockito.any());
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		NotificationResultCapture notificationResultCapture() {
			return new NotificationResultCapture();
		}
	}

	static class NotificationResultCapture {

		private final AtomicReference<CountDownLatch> sentLatch = new AtomicReference<>(new CountDownLatch(0));
		private final AtomicReference<CountDownLatch> failedLatch = new AtomicReference<>(new CountDownLatch(0));
		private final AtomicReference<NotificationSentEvent> sentEventRef = new AtomicReference<>();
		private final AtomicReference<NotificationFailedEvent> failedEventRef = new AtomicReference<>();

		@KafkaListener(topics = NotificationTopics.SENT, groupId = "notification-worker-sent-test-listener")
		void listenSent(NotificationSentEvent event) {
			sentEventRef.set(event);
			sentLatch.get().countDown();
		}

		@KafkaListener(topics = NotificationTopics.FAILED, groupId = "notification-worker-failed-test-listener")
		void listenFailed(NotificationFailedEvent event) {
			failedEventRef.set(event);
			failedLatch.get().countDown();
		}

		void reset() {
			sentEventRef.set(null);
			failedEventRef.set(null);
			sentLatch.set(new CountDownLatch(1));
			failedLatch.set(new CountDownLatch(1));
		}

		NotificationSentEvent awaitSent() throws InterruptedException {
			boolean completed = sentLatch.get().await(10, TimeUnit.SECONDS);
			if (!completed) {
				return null;
			}
			return sentEventRef.get();
		}

		NotificationFailedEvent awaitFailed() throws InterruptedException {
			boolean completed = failedLatch.get().await(10, TimeUnit.SECONDS);
			if (!completed) {
				return null;
			}
			return failedEventRef.get();
		}
	}
}
