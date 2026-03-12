package com.study.notification.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.notification.contract.NotificationFailedEvent;
import com.study.notification.contract.NotificationRequestedEvent;
import com.study.notification.contract.NotificationSentEvent;
import com.study.notification.contract.NotificationTopics;
import com.study.notification.domain.NotificationChannel;
import com.study.notification.producer.web.NotificationAcceptedResponse;
import com.study.notification.producer.web.NotificationStatusResponse;

@SpringBootTest(
	classes = {NotificationProducerApplication.class, NotificationProducerApplicationTests.TestConfig.class},
	properties = {
		"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.group-id=notification-producer-test-group",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
		"spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
		"spring.kafka.consumer.properties.spring.json.trusted.packages=com.study.notification.contract,com.study.notification.domain",
		// 테스트용 H2 인메모리 DB
		"spring.datasource.url=jdbc:h2:mem:producer-test;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.flyway.enabled=false",
		// OutboxRelay를 빠르게 실행하여 테스트 속도 개선
		"app.notification.outbox.poll-interval-ms=100"
		// Pillar 1 개선: KafkaTemplate이 transactional이므로 producer 멱등성/acks 기본값 유지.
		// EmbeddedKafka brokerProperties에서 TX 관련 브로커 설정을 별도로 구성한다.
	}
)
@AutoConfigureMockMvc
// EmbeddedKafka: Kafka 트랜잭션에 필요한 브로커 설정 추가 (Pillar 1 개선)
@EmbeddedKafka(
	partitions = 1,
	topics = {NotificationTopics.REQUESTED, NotificationTopics.SENT, NotificationTopics.FAILED},
	brokerProperties = {
		"transaction.state.log.replication.factor=1",
		"transaction.state.log.min.isr=1",
		"transaction.state.log.num.partitions=1"
	}
)
class NotificationProducerApplicationTests {

	private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);
	private static final long QUERY_POLL_MILLIS = 100L;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RequestedEventCapture requestedEventCapture;

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("알림 요청 API를 호출하면 OutboxRelay가 notification.requested 이벤트를 발행한다.")
	void postNotificationPublishesRequestedEvent() throws Exception {
		requestedEventCapture.reset();

		mockMvc.perform(post("/api/notifications")
			.contentType(APPLICATION_JSON)
			.content("""
				{
				  "channel": "EMAIL",
				  "recipient": "user@example.com",
				  "subject": "welcome",
				  "body": "hello",
				  "templateCode": "WELCOME"
				}
				"""))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.notificationId").isNotEmpty())
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));

		// OutboxRelay가 비동기로 발행하므로 이벤트 수신을 대기
		NotificationRequestedEvent event = requestedEventCapture.await();
		assertThat(event).isNotNull();
		assertThat(event.channel().name()).isEqualTo("EMAIL");
		assertThat(event.recipient()).isEqualTo("user@example.com");
		assertThat(event.subject()).isEqualTo("welcome");
		assertThat(event.body()).isEqualTo("hello");
		assertThat(event.templateCode()).isEqualTo("WELCOME");
		assertThat(event.notificationId()).isNotBlank();
		assertThat(event.traceId()).isNotBlank();
	}

	@Test
	@DisplayName("접수 직후 조회 API를 호출하면 ACCEPTED 상태를 반환한다.")
	void getNotificationReturnsAcceptedStatusRightAfterPost() throws Exception {
		NotificationAcceptedResponse acceptedResponse = createNotification("EMAIL", "WELCOME");

		mockMvc.perform(get("/api/notifications/{notificationId}", acceptedResponse.notificationId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.notificationId").value(acceptedResponse.notificationId()))
			.andExpect(jsonPath("$.traceId").value(acceptedResponse.traceId()))
			.andExpect(jsonPath("$.channel").value("EMAIL"))
			.andExpect(jsonPath("$.recipient").value("user@example.com"))
			.andExpect(jsonPath("$.subject").value("welcome"))
			.andExpect(jsonPath("$.templateCode").value("WELCOME"))
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
	}

	@Test
	@DisplayName("성공 이벤트를 받으면 조회 API가 SENT 상태로 갱신된다.")
	void getNotificationReturnsSentStatusAfterSentEvent() throws Exception {
		NotificationAcceptedResponse acceptedResponse = createNotification("EMAIL", "WELCOME");

		// KafkaTemplate이 transactional이므로 executeInTransaction으로 랩핑하여 메시지 전송
		kafkaTemplate.executeInTransaction(ops -> ops.send(
			NotificationTopics.SENT,
			acceptedResponse.notificationId(),
			new NotificationSentEvent(
				acceptedResponse.notificationId(),
				acceptedResponse.traceId(),
				NotificationChannel.EMAIL,
				"SandboxEmailSender"
			)
		));

		NotificationStatusResponse response = awaitNotificationStatus(
			acceptedResponse.notificationId(),
			"SENT"
		);
		assertThat(response.provider()).isEqualTo("SandboxEmailSender");
		assertThat(response.reason()).isNull();
	}

	@Test
	@DisplayName("실패 이벤트를 받으면 조회 API가 FAILED 상태로 갱신된다.")
	void getNotificationReturnsFailedStatusAfterFailedEvent() throws Exception {
		NotificationAcceptedResponse acceptedResponse = createNotification("SLACK", "WELCOME");

		kafkaTemplate.executeInTransaction(ops -> ops.send(
			NotificationTopics.FAILED,
			acceptedResponse.notificationId(),
			new NotificationFailedEvent(
				acceptedResponse.notificationId(),
				acceptedResponse.traceId(),
				NotificationChannel.SLACK,
				"2회 시도 후 실패"
			)
		));

		NotificationStatusResponse response = awaitNotificationStatus(
			acceptedResponse.notificationId(),
			"FAILED"
		);
		assertThat(response.provider()).isNull();
		assertThat(response.reason()).isEqualTo("2회 시도 후 실패");
	}

	private NotificationAcceptedResponse createNotification(String channel, String templateCode) throws Exception {
		MvcResult mvcResult = mockMvc.perform(post("/api/notifications")
			.contentType(APPLICATION_JSON)
			.content("""
				{
				  "channel": "%s",
				  "recipient": "user@example.com",
				  "subject": "welcome",
				  "body": "hello",
				  "templateCode": "%s"
				}
				""".formatted(channel, templateCode)))
			.andExpect(status().isAccepted())
			.andReturn();
		return objectMapper.readValue(
			mvcResult.getResponse().getContentAsString(),
			NotificationAcceptedResponse.class
		);
	}

	private NotificationStatusResponse awaitNotificationStatus(String notificationId, String expectedStatus) throws Exception {
		long deadline = System.nanoTime() + QUERY_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			MvcResult mvcResult = mockMvc.perform(get("/api/notifications/{notificationId}", notificationId))
				.andExpect(status().isOk())
				.andReturn();
			NotificationStatusResponse response = objectMapper.readValue(
				mvcResult.getResponse().getContentAsString(),
				NotificationStatusResponse.class
			);
			if (expectedStatus.equals(response.status().name())) {
				return response;
			}
			Thread.sleep(QUERY_POLL_MILLIS);
		}
		throw new AssertionError("기대한 상태로 갱신되지 않았습니다: " + expectedStatus);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		RequestedEventCapture requestedEventCapture() {
			return new RequestedEventCapture();
		}
	}

	static class RequestedEventCapture {

		private final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(0));
		private final AtomicReference<NotificationRequestedEvent> eventRef = new AtomicReference<>();

		@KafkaListener(topics = NotificationTopics.REQUESTED, groupId = "notification-producer-test-listener")
		void listen(NotificationRequestedEvent event) {
			eventRef.set(event);
			latch.get().countDown();
		}

		void reset() {
			eventRef.set(null);
			latch.set(new CountDownLatch(1));
		}

		NotificationRequestedEvent await() throws InterruptedException {
			boolean completed = latch.get().await(10, TimeUnit.SECONDS);
			if (!completed) {
				return null;
			}
			return eventRef.get();
		}
	}
}
