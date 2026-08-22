package com.study.kafka.retry.topicpresence

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.CustomRetryDltConfiguration
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class TopicPresenceRecorder {

	private val hops = ConcurrentLinkedQueue<Pair<String, String>>()

	fun record(payload: String, topic: String) {
		hops.add(payload to topic)
	}

	fun hopsFor(payload: String): List<String> = hops.filter { it.first == payload }.map { it.second }

	fun awaitHops(payload: String, atLeast: Int, seconds: Long) =
		awaitUntil(seconds) { hopsFor(payload).size >= atLeast }

	fun awaitTopic(payload: String, topic: String, seconds: Long) =
		awaitUntil(seconds) { hopsFor(payload).contains(topic) }

	private fun awaitUntil(seconds: Long, condition: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
		while (System.nanoTime() < deadline) {
			if (condition()) return true
			Thread.sleep(100)
		}
		return false
	}
}

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class TopicPresenceApplication {

	@Bean
	fun topicPresenceRecorder() = TopicPresenceRecorder()

	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })
}

private val log = LoggerFactory.getLogger("topic-presence")

private fun alwaysFail(payload: String, topic: String, recorder: TopicPresenceRecorder): Nothing {
	recorder.record(payload, topic)
	log.info("consume topic={} payload={}", topic, payload)
	throw IllegalStateException("항상 실패: $payload")
}

/**
 * 블로킹 전용인데 예전 논블로킹 설정이 남긴 재시도 토픽이 브로커에 그대로 있는 리스너.
 *
 * `attempts=1`이므로 목적지 체인은 원본과 DLT뿐이다. 남아 있는 재시도 토픽은 체인에 없다.
 */
@Component
class StaleRetryTopicListener(private val recorder: TopicPresenceRecorder) {

	@CustomRetryAndDLT(attempts = "1", blockingAttempts = "3", blockingBackoffDelay = "50")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-presence-stale")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit =
		alwaysFail(payload, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.record(payload, topic)
	}

	companion object {
		const val TOPIC = "presence-stale"
		const val STALE_RETRY_TOPIC = "presence-stale-retry-0"
		const val DLT_TOPIC = "presence-stale-dlt"
		const val LISTENER_ID = "presence-stale-listener"
		const val BLOCKING_ATTEMPTS = 3
	}
}

/**
 * 블로킹 전용인데 재시도 토픽이 아예 없는 리스너.
 *
 * `attempts=1`이라 목적지 체인은 원본과 DLT뿐이므로 재시도 토픽은 필요하지 않다.
 * `autoCreateTopics=false`이고 브로커 자동 생성도 꺼져 있으므로, DLT만 미리 만들어 두면
 * 재시도 토픽 없이도 끝까지 흘러야 한다.
 */
@Component
class BlockingWithoutRetryTopicListener(private val recorder: TopicPresenceRecorder) {

	@CustomRetryAndDLT(
		attempts = "1",
		blockingAttempts = "3",
		blockingBackoffDelay = "50",
		autoCreateTopics = "false",
	)
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-presence-blocking")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit =
		alwaysFail(payload, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.record(payload, topic)
	}

	companion object {
		const val TOPIC = "presence-blocking"
		const val DLT_TOPIC = "presence-blocking-dlt"
		const val LISTENER_ID = "presence-blocking-listener"
		const val BLOCKING_ATTEMPTS = 3
	}
}

/**
 * 논블로킹인데 재시도 토픽과 DLT를 미리 만들어 둔 리스너.
 *
 * `autoCreateTopics=false`로 운영하는 정상적인 모습이다. 기동 검사를 통과하고 체인도 정상 동작해야 한다.
 */
@Component
class NonBlockingPresentTopicsListener(private val recorder: TopicPresenceRecorder) {

	@CustomRetryAndDLT(attempts = "3", autoCreateTopics = "false")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-presence-ok")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit =
		alwaysFail(payload, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.record(payload, topic)
	}

	companion object {
		const val TOPIC = "presence-ok"
		const val RETRY_0 = "presence-ok-retry-0"
		const val RETRY_1 = "presence-ok-retry-1"
		const val DLT_TOPIC = "presence-ok-dlt"
		const val LISTENER_ID = "presence-ok-listener"
	}
}

/**
 * 기동 후 토픽을 지워 검사기가 무엇을 잡아내는지 확인하기 위한 리스너.
 *
 * 다른 테스트가 쓰는 토픽을 지우면 서로 간섭하므로 전용으로 하나 둔다.
 * `attempts=2`라 재시도 토픽은 하나이고, 간격 재사용 전략 기본값에 따라 인덱스가 붙지 않는다.
 */
@Component
class DeletableTopicsListener(private val recorder: TopicPresenceRecorder) {

	@CustomRetryAndDLT(attempts = "2", autoCreateTopics = "false")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-presence-del")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit =
		alwaysFail(payload, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.record(payload, topic)
	}

	companion object {
		const val TOPIC = "presence-del"
		const val RETRY_TOPIC = "presence-del-retry"
		const val DLT_TOPIC = "presence-del-dlt"
		const val LISTENER_ID = "presence-del-listener"
	}
}
