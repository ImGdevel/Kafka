package com.study.kafka.retry.topicpresence

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 재시도 토픽이 있어야 할 때 없거나, 없어야 할 때 남아 있는 두 경우를 확인한다.
 *
 * 브로커 자동 생성을 꺼 둔다. 그래야 재시도 토픽이 정말로 존재하지 않는 상태를 만들 수 있다.
 * 운영에서 토픽 생성 권한이 없거나 누가 토픽을 지웠을 때가 이 상태다.
 *
 * 발행에 4초 이상 걸리지 않도록 프로듀서 `max.block.ms`를 낮춘다.
 * 없는 토픽으로 보내면 메타데이터를 기다리다 타임아웃하는데, 기본값이면 60초씩 잡아먹는다.
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
	// presence-stale 은 예전 논블로킹 설정이 남긴 것처럼 재시도 토픽을 미리 만들어 둔다.
	topics = [
		StaleRetryTopicListener.TOPIC,
		StaleRetryTopicListener.STALE_RETRY_TOPIC,
		// 블로킹 전용은 재시도 토픽 없이 DLT만 있으면 된다.
		BlockingWithoutRetryTopicListener.TOPIC,
		BlockingWithoutRetryTopicListener.DLT_TOPIC,
	],
	brokerProperties = ["auto.create.topics.enable=false"],
)
@DisplayName("재시도 토픽의 존재 여부")
class RetryTopicPresenceTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: TopicPresenceRecorder

	@Autowired
	private lateinit var kafkaAdmin: KafkaAdmin

	private fun brokerTopics(prefix: String) =
		AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			admin.listTopics().names().get(10, TimeUnit.SECONDS).filter { it.startsWith(prefix) }.sorted()
		}


	@Test
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
		// 토픽 자체는 그대로 남아 있다. 지워지지도, 비워지지도 않는다.
		assertFalse(brokerTopics(StaleRetryTopicListener.STALE_RETRY_TOPIC).isEmpty())
	}


	@Test
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
}
