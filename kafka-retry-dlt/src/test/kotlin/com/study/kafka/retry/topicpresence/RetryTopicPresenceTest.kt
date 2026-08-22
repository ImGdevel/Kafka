package com.study.kafka.retry.topicpresence

import com.study.kafka.retry.RetryTopicPresenceValidator
import com.study.kafka.retry.startupfail.StartupFailApplication
import com.study.kafka.retry.startupfail.StartupFailListener
import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 재시도 토픽과 DLT가 브로커에 있어야 할 때 없거나, 없어야 할 때 남아 있는 경우를 확인한다.
 *
 * 브로커 자동 생성을 꺼 둔다. 그래야 `autoCreateTopics=false`인 리스너의 토픽이 정말로
 * 없는 상태를 만들 수 있다. 운영에서 토픽 생성 권한이 없을 때가 이 상태다.
 *
 * 이 컨텍스트가 뜬다는 것 자체가 기동 검사를 통과했다는 뜻이다.
 * `autoCreateTopics=false`인 리스너들의 토픽을 모두 미리 만들어 두었다.
 * 검사에 걸리는 쪽은 기동 자체가 실패해 이 방식으로는 확인할 수 없으므로,
 * 기동 후 토픽을 지우고 검사기를 직접 불러 확인한다.
 */
@SpringBootTest(
	classes = [TopicPresenceApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"spring.kafka.producer.properties.max.block.ms=4000",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(
	partitions = 1,
	topics = [
		// autoCreateTopics=false 인 리스너들의 토픽은 전부 미리 만들어 둔다.
		NonBlockingPresentTopicsListener.TOPIC,
		NonBlockingPresentTopicsListener.RETRY_0,
		NonBlockingPresentTopicsListener.RETRY_1,
		NonBlockingPresentTopicsListener.DLT_TOPIC,
		DeletableTopicsListener.TOPIC,
		DeletableTopicsListener.RETRY_TOPIC,
		DeletableTopicsListener.DLT_TOPIC,
		BlockingWithoutRetryTopicListener.TOPIC,
		BlockingWithoutRetryTopicListener.DLT_TOPIC,
		// 블로킹 전용으로 바뀌면서 쓸모없어진 재시도 토픽이 남아 있는 상황
		StaleRetryTopicListener.TOPIC,
		StaleRetryTopicListener.STALE_RETRY_TOPIC,
	],
	brokerProperties = ["auto.create.topics.enable=false"],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("재시도 토픽의 존재 여부")
class RetryTopicPresenceTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: TopicPresenceRecorder

	@Autowired
	private lateinit var kafkaAdmin: KafkaAdmin

	@Autowired
	private lateinit var presenceValidator: RetryTopicPresenceValidator

	private fun brokerTopics(prefix: String) =
		AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			admin.listTopics().names().get(10, TimeUnit.SECONDS).filter { it.startsWith(prefix) }.sorted()
		}

	private fun deleteTopic(topic: String) {
		AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			admin.deleteTopics(listOf(topic)).all().get(30, TimeUnit.SECONDS)
		}
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
		while (System.nanoTime() < deadline) {
			if (topic !in brokerTopics(topic)) return
			Thread.sleep(100)
		}
		error("$topic 삭제가 반영되지 않았다")
	}

	@Test
	@Order(1)
	@DisplayName("autoCreateTopics=false 라도 토픽이 갖춰져 있으면 기동하고 체인도 정상 동작한다")
	fun `non blocking chain works when topics are pre created`() {
		kafkaTemplate.send(NonBlockingPresentTopicsListener.TOPIC, "present")

		assertTrue(
			recorder.awaitTopic("present", NonBlockingPresentTopicsListener.DLT_TOPIC, 60),
			"DLT까지 가지 못했다 → ${recorder.hopsFor("present")}",
		)

		assertEquals(
			listOf(
				NonBlockingPresentTopicsListener.TOPIC,
				NonBlockingPresentTopicsListener.RETRY_0,
				NonBlockingPresentTopicsListener.RETRY_1,
				NonBlockingPresentTopicsListener.DLT_TOPIC,
			),
			recorder.hopsFor("present"),
		)
	}

	@Test
	@Order(2)
	@DisplayName("블로킹 전용은 재시도 토픽이 없어도 DLT까지 정상 동작한다")
	fun `blocking only works without any retry topic`() {
		kafkaTemplate.send(BlockingWithoutRetryTopicListener.TOPIC, "blocking-no-retry")

		assertTrue(
			recorder.awaitTopic("blocking-no-retry", BlockingWithoutRetryTopicListener.DLT_TOPIC, 60),
			"DLT까지 가지 못했다 → ${recorder.hopsFor("blocking-no-retry")}",
		)
		Thread.sleep(2_000)

		assertEquals(
			List(BlockingWithoutRetryTopicListener.BLOCKING_ATTEMPTS) { BlockingWithoutRetryTopicListener.TOPIC } +
				BlockingWithoutRetryTopicListener.DLT_TOPIC,
			recorder.hopsFor("blocking-no-retry"),
		)

		// 재시도 토픽은 끝까지 만들어지지 않았다. 블로킹 경로는 그것을 필요로 하지 않는다.
		assertEquals(
			listOf(BlockingWithoutRetryTopicListener.TOPIC, BlockingWithoutRetryTopicListener.DLT_TOPIC),
			brokerTopics(BlockingWithoutRetryTopicListener.TOPIC),
		)
	}

	@Test
	@Order(3)
	@DisplayName("블로킹 전용이면 남아 있는 재시도 토픽을 타지 않는다")
	fun `blocking only routing ignores a stale retry topic`() {
		kafkaTemplate.send(StaleRetryTopicListener.TOPIC, "stale-present")

		assertTrue(
			recorder.awaitTopic("stale-present", StaleRetryTopicListener.DLT_TOPIC, 60),
			"DLT까지 가지 못했다 → ${recorder.hopsFor("stale-present")}",
		)
		Thread.sleep(2_000)

		// 목적지 체인은 attempts로 계산된다. 브로커에 재시도 토픽이 남아 있어도 경로에 끼어들지 않는다.
		assertEquals(
			List(StaleRetryTopicListener.BLOCKING_ATTEMPTS) { StaleRetryTopicListener.TOPIC } +
				StaleRetryTopicListener.DLT_TOPIC,
			recorder.hopsFor("stale-present"),
		)
	}

	@Test
	@Order(4)
	@DisplayName("남아 있는 재시도 토픽의 메시지는 아무도 소비하지 않는다")
	fun `messages left in a stale retry topic are never consumed`() {
		// 설정을 논블로킹에서 블로킹으로 바꾸기 전에 재시도 토픽에 쌓여 있던 메시지를 흉내낸다.
		kafkaTemplate.send(StaleRetryTopicListener.STALE_RETRY_TOPIC, "stranded")

		// 다른 메시지가 DLT까지 도는 동안 충분히 기다려 본다.
		kafkaTemplate.send(StaleRetryTopicListener.TOPIC, "canary")
		assertTrue(recorder.awaitTopic("canary", StaleRetryTopicListener.DLT_TOPIC, 60))
		Thread.sleep(3_000)

		// 블로킹 전용 구성에는 재시도 토픽을 구독하는 리스너가 없다. 메시지는 방치된다.
		assertEquals(
			emptyList(),
			recorder.hopsFor("stranded"),
			"구독자가 없어야 할 재시도 토픽에서 메시지가 소비됐다",
		)
		assertFalse(brokerTopics(StaleRetryTopicListener.STALE_RETRY_TOPIC).isEmpty())
	}

	@Test
	@Order(5)
	@DisplayName("재시도 토픽이 사라지면 검사기가 잡아낸다")
	fun `validator reports a missing retry topic`() {
		deleteTopic(DeletableTopicsListener.RETRY_TOPIC)

		val failure = assertFailsWith<IllegalStateException> { presenceValidator.validate() }

		assertTrue(
			failure.message?.contains(DeletableTopicsListener.RETRY_TOPIC) == true,
			"어떤 토픽이 없는지 알려주지 않는다 → ${failure.message}",
		)
		assertTrue(
			failure.message?.contains(DeletableTopicsListener.LISTENER_ID.let { "DeletableTopicsListener" }) == true,
			"어느 리스너인지 알려주지 않는다 → ${failure.message}",
		)
	}

	@Test
	@Order(6)
	@DisplayName("DLT를 쓰겠다고 해놓고 DLT 토픽이 없어도 검사기가 잡아낸다")
	fun `validator reports a missing dlt topic`() {
		deleteTopic(DeletableTopicsListener.DLT_TOPIC)

		val failure = assertFailsWith<IllegalStateException> { presenceValidator.validate() }

		assertTrue(
			failure.message?.contains(DeletableTopicsListener.DLT_TOPIC) == true,
			"없는 DLT 토픽을 알려주지 않는다 → ${failure.message}",
		)
	}

	@Test
	@Order(7)
	@DisplayName("논블로킹인데 재시도 토픽이 없으면 기동 자체가 실패한다")
	fun `startup fails when a required topic is missing`() {
		// 같은 임베디드 브로커를 바라보는 두 번째 컨텍스트를 띄운다.
		// 검사에 걸리는 쪽은 기동이 실패하므로 @SpringBootTest 로는 확인할 수 없다.
		val brokers = System.getProperty("spring.embedded.kafka.brokers")

		val failure = assertFailsWith<Throwable> {
			SpringApplicationBuilder(StartupFailApplication::class.java)
				.web(WebApplicationType.NONE)
				.properties(
					"spring.kafka.bootstrap-servers=$brokers",
					"spring.main.banner-mode=off",
					"app.kafka.retry.replication-factor=1",
				)
				.run()
				.close()
		}

		// 기동 실패는 여러 겹으로 감싸여 올라온다. 원인 체인을 펼쳐서 본다.
		val message = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(separator = " | ")
		assertTrue(message.contains("autoCreateTopics=false"), "검사기가 낸 실패가 아니다 → $message")
		assertTrue(
			message.contains("${StartupFailListener.TOPIC}-retry-0"),
			"없는 재시도 토픽을 알려주지 않는다 → $message",
		)
		assertTrue(
			message.contains("${StartupFailListener.TOPIC}-dlt"),
			"없는 DLT 토픽을 알려주지 않는다 → $message",
		)
	}
}
