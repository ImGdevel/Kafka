package com.study.kafka.retry.pollinterval

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

class PollIntervalRecorder {
	val listenerDeliveries = ConcurrentLinkedQueue<String>()
	val dltDeliveries = ConcurrentLinkedQueue<String>()

	fun countListener(payload: String) = listenerDeliveries.count { it == payload }
	fun countDlt(payload: String) = dltDeliveries.count { it == payload }

	fun awaitDlt(payload: String, seconds: Long): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
		while (System.nanoTime() < deadline) {
			if (countDlt(payload) > 0) return true
			Thread.sleep(50)
		}
		return false
	}
}

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class PollIntervalApplication {
	@Bean fun pollIntervalRecorder() = PollIntervalRecorder()
	@Bean fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })
}

private val log = LoggerFactory.getLogger("poll-interval")

/**
 * 개별 백오프는 max.poll.interval.ms 보다 짧지만, 전체 블로킹 구간(3회 × 3초)은 그보다 긴 리스너.
 *
 * `blockingBackoffDelay * (blockingAttempts - 1)` 를 상한과 비교해야 한다는 통념이 맞는지 본다.
 */
@Component
class TotalWindowExceedsListener(private val recorder: PollIntervalRecorder) {

	@CustomRetryAndDLT(attempts = "1", blockingAttempts = "3", blockingBackoffDelay = "3000")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-poll-total")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		log.info("[total] consume topic={} 누적={}", topic, recorder.countListener(payload))
		throw IllegalStateException("항상 실패")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("[total] DLT topic={}", topic)
		recorder.dltDeliveries.add(payload)
	}

	companion object {
		const val TOPIC = "poll-total"
		const val LISTENER_ID = "poll-total-listener"
	}
}

/** 개별 백오프 하나가 이미 max.poll.interval.ms 를 넘는 리스너. */
@Component
class SingleBackoffExceedsListener(private val recorder: PollIntervalRecorder) {

	@CustomRetryAndDLT(attempts = "1", blockingAttempts = "2", blockingBackoffDelay = "9000")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-poll-single")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		log.info("[single] consume topic={} 누적={}", topic, recorder.countListener(payload))
		throw IllegalStateException("항상 실패")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("[single] DLT topic={}", topic)
		recorder.dltDeliveries.add(payload)
	}

	companion object {
		const val TOPIC = "poll-single"
		const val LISTENER_ID = "poll-single-listener"
	}
}
