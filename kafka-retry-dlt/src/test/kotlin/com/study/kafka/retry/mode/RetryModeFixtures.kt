package com.study.kafka.retry.mode

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.CustomRetryDltConfiguration
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.retrytopic.DltStrategy
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 리스너별로 "어떤 토픽을 몇 번 방문했는지"를 순서대로 기록한다.
 *
 * 기대 방문 수만큼 래치를 걸어 두고, 래치가 열린 뒤 추가로 잠깐 더 기다려
 * "선택하지 않은 노선을 타지 않는다"까지 확인할 수 있게 한다.
 */
class ModeRecorder {

	private val visits = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
	private val latches = ConcurrentHashMap<String, CountDownLatch>()

	fun expect(key: String, count: Int) {
		visits[key] = ConcurrentLinkedQueue()
		latches[key] = CountDownLatch(count)
	}

	fun record(key: String, topic: String) {
		visits.computeIfAbsent(key) { ConcurrentLinkedQueue() }.add(topic)
		latches[key]?.countDown()
	}

	fun await(key: String, seconds: Long = 30): Boolean =
		latches[key]?.await(seconds, TimeUnit.SECONDS) ?: false

	fun of(key: String): List<String> = visits[key]?.toList() ?: emptyList()
}

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class RetryModeTestApplication {

	@Bean
	fun modeRecorder() = ModeRecorder()

	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })
}

private val log = LoggerFactory.getLogger("retry-mode")

private fun fail(key: String, topic: String, recorder: ModeRecorder): Nothing {
	recorder.record(key, topic)
	log.info("[{}] consume topic={}", key, topic)
	throw IllegalStateException("항상 실패: $key")
}

/** 1분면: 블로킹 재시도 + DLT. attempts=1이라 재시도 토픽은 만들어지지 않는다. */
@Component
class BlockingWithDltListener(private val recorder: ModeRecorder) {

	@CustomRetryAndDLT(
		attempts = "1",
		blockingAttempts = "3",
		blockingBackoffDelay = "50",
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		owner = "blocking-dlt-team",
	)
	@KafkaListener(topics = [TOPIC], groupId = "g-blocking-dlt")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit = fail(KEY, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) = recorder.record(KEY, topic)

	companion object {
		const val TOPIC = "mode-bd"
		const val KEY = "blocking+dlt"
	}
}

/** 2분면: 블로킹 재시도 + DLT 없음. 소진되면 메시지는 폐기된다. */
@Component
class BlockingWithoutDltListener(private val recorder: ModeRecorder) {

	@CustomRetryAndDLT(
		attempts = "1",
		blockingAttempts = "3",
		blockingBackoffDelay = "50",
		dltStrategy = DltStrategy.NO_DLT,
		owner = "blocking-nodlt-team",
	)
	@KafkaListener(topics = [TOPIC], groupId = "g-blocking-nodlt")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit = fail(KEY, topic, recorder)

	companion object {
		const val TOPIC = "mode-bn"
		const val KEY = "blocking+noDlt"
	}
}

/** 3분면: 논블로킹 재시도 + DLT. blockingAttempts 기본값 1이라 로컬 반복은 없다. */
@Component
class NonBlockingWithDltListener(private val recorder: ModeRecorder) {

	@CustomRetryAndDLT(
		attempts = "3",
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		owner = "nonblocking-dlt-team",
	)
	@KafkaListener(topics = [TOPIC], groupId = "g-nonblocking-dlt")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit = fail(KEY, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) = recorder.record(KEY, topic)

	companion object {
		const val TOPIC = "mode-nd"
		const val KEY = "nonBlocking+dlt"
	}
}

/** 4분면: 논블로킹 재시도 + DLT 없음. 마지막 재시도 토픽에서 실패하면 폐기된다. */
@Component
class NonBlockingWithoutDltListener(private val recorder: ModeRecorder) {

	@CustomRetryAndDLT(
		attempts = "3",
		dltStrategy = DltStrategy.NO_DLT,
		owner = "nonblocking-nodlt-team",
	)
	@KafkaListener(topics = [TOPIC], groupId = "g-nonblocking-nodlt")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit = fail(KEY, topic, recorder)

	companion object {
		const val TOPIC = "mode-nn"
		const val KEY = "nonBlocking+noDlt"
	}
}

/** blockingRetryOn에 없는 예외는 블로킹 대상이 아니라는 것을 확인하는 리스너. */
@Component
class BlockingFilteredListener(private val recorder: ModeRecorder) {

	@CustomRetryAndDLT(
		attempts = "1",
		blockingAttempts = "3",
		blockingBackoffDelay = "50",
		blockingRetryOn = [IllegalArgumentException::class],
		owner = "blocking-filtered-team",
	)
	@KafkaListener(topics = [TOPIC], groupId = "g-blocking-filtered")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit = fail(KEY, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) = recorder.record(KEY, topic)

	companion object {
		const val TOPIC = "mode-bx"
		const val KEY = "blocking+filtered"
	}
}
