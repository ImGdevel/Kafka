package com.study.kafka.retry.chain

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.study.kafka.retry.CustomRetryDltPolicyRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `@CustomRetryAndDLT`가 실제 브로커에서 재시도 체인을 만들어 내는지 확인한다.
 *
 * 확인 명제
 *  1. 성공 메시지는 원본 토픽에서 한 번만 소비된다.
 *  2. 실패 메시지는 `attempts` 횟수만큼 원본 → 재시도 토픽을 거친 뒤 DLT 핸들러로 넘어간다.
 *  3. 재시도 토픽 이름이 `topicSuffixingStrategy`(SUFFIX_WITH_INDEX_VALUE) 기본값을 따른다.
 *
 * 백오프는 테스트 속도를 위해 100ms/×2로 낮춘다. 지수(multiplier=2.0)를 유지하는 이유는
 * 간격이 같아지면 `sameIntervalTopicReuseStrategy=SINGLE_TOPIC`이 재시도 토픽을 하나로 합쳐
 * 체인 모양 자체가 달라지기 때문이다.
 */
@SpringBootTest(
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"app.kafka.retry.attempts=3",
		"app.kafka.retry.backoff.delay=100",
		"app.kafka.retry.backoff.multiplier=2.0",
		"app.kafka.retry.backoff.max-delay=1000",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(partitions = 1, topics = [OrderEventListener.TOPIC, AuditEventListener.TOPIC])
@DisplayName("@CustomRetryAndDLT 재시도 체인 통합 검증")
class CustomRetryAndDLTChainTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: RetryChainRecorder

	@Autowired
	private lateinit var kafkaAdmin: KafkaAdmin

	@Autowired
	private lateinit var policyRegistry: CustomRetryDltPolicyRegistry

	@BeforeEach
	fun resetRecorder() = recorder.reset()

	@Test
	@DisplayName("실패 메시지는 orders → orders-retry-0 → orders-retry-1 → orders-dlt 순으로 흐른다")
	fun `poison message walks the whole retry chain into the dlt`() {
		kafkaTemplate.send(OrderEventListener.TOPIC, "poison-1")

		assertTrue(
			recorder.dltLatch.await(30, TimeUnit.SECONDS),
			"30초 안에 DLT 핸들러가 호출되지 않았다",
		)

		assertEquals(
			listOf("orders", "orders-retry-0", "orders-retry-1", "orders-dlt"),
			recorder.topicsFor("poison-1"),
		)
	}

	@Test
	@DisplayName("성공 메시지는 원본 토픽에서 한 번만 소비된다")
	fun `successful message is consumed once`() {
		kafkaTemplate.send(OrderEventListener.TOPIC, "ok-1")

		// 재시도가 일어난다면 첫 백오프(100ms) 이후 재시도 토픽에 기록이 남는다.
		Thread.sleep(2_000)

		assertEquals(listOf("orders"), recorder.topicsFor("ok-1"))
	}

	@Test
	@DisplayName("재시도/DLT 토픽이 브로커에 자동 생성된다")
	fun `retry and dlt topics are auto created`() {
		kafkaTemplate.send(OrderEventListener.TOPIC, "poison-2")
		assertTrue(recorder.dltLatch.await(30, TimeUnit.SECONDS))

		AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			val topics = admin.listTopics().names().get(10, TimeUnit.SECONDS)
			assertTrue(topics.containsAll(listOf("orders", "orders-retry-0", "orders-retry-1", "orders-dlt")))
		}
	}

	@Test
	@DisplayName("owner가 DLT 알림에 실려 나간다")
	fun `owner is carried into the dlt alert`() {
		kafkaTemplate.send(OrderEventListener.TOPIC, "poison-3")
		assertTrue(recorder.dltLatch.await(30, TimeUnit.SECONDS), "30초 안에 DLT 핸들러가 호출되지 않았다")

		val alerts = recorder.alertsFor("poison-3")
		assertEquals(1, alerts.size)
		with(alerts.single()) {
			assertEquals("order-team", owner)
			assertEquals("orders-dlt", dltTopic)
			assertEquals("OrderEventListener#handle", listenerId)
			assertTrue(reason?.contains("처리 불가 메시지") == true, "실패 사유가 알림에 담기지 않았다: $reason")
		}
	}

	@Test
	@DisplayName("alertOnDlt=false인 리스너는 DLT에 적재돼도 알림을 보내지 않는다")
	fun `alert is suppressed when alertOnDlt is false`() {
		kafkaTemplate.send(AuditEventListener.TOPIC, "poison-4")
		assertTrue(recorder.dltLatch.await(30, TimeUnit.SECONDS), "30초 안에 DLT 핸들러가 호출되지 않았다")

		// attempts=2 이므로 원본 1회 + 재시도 1회 후 DLT로 간다.
		// 재시도 토픽이 하나뿐이면 SUFFIX_WITH_INDEX_VALUE라도 인덱스를 붙이지 않아 이름이 `audits-retry`가 된다.
		assertEquals(listOf("audits", "audits-retry", "audits-dlt"), recorder.topicsFor("poison-4"))
		assertEquals(emptyList(), recorder.allAlerts())
	}

	@Test
	@DisplayName("레지스트리가 리스너별 확장 속성을 색인한다")
	fun `registry indexes extension attributes per listener`() {
		with(assertNotNull(policyRegistry.findByOriginalTopic(OrderEventListener.TOPIC))) {
			assertEquals("order-team", owner)
			assertTrue(alertOnDlt)
			assertEquals(listOf("orders-dlt"), dltTopics)
		}
		with(assertNotNull(policyRegistry.findByTopic("audits-dlt"))) {
			assertEquals("audit-team", owner)
			assertEquals(false, alertOnDlt)
		}
	}
}
