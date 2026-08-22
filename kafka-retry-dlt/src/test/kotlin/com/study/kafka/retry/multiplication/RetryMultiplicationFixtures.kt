package com.study.kafka.retry.multiplication

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.retry.annotation.Backoff
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import org.springframework.util.backoff.FixedBackOff
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class MultiplyRecorder {

	val hops = ConcurrentLinkedQueue<String>()

	fun countFor(topic: String) = hops.count { it == topic }

	// MultiplyListener는 순정 @RetryableTopic을 쓴다. Spring 기본 DLT 접미사는 하이픈(-dlt)이다.
	// 우리 @CustomRetryAndDLT의 점(.dlt) 관례와 다르니 섞어 쓰면 안 된다.
	fun awaitDlt(seconds: Long): Boolean =
		awaitUntil(seconds) { hops.any { hop -> hop.endsWith("-dlt") } }

	private fun awaitUntil(seconds: Long, condition: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
		while (System.nanoTime() < deadline) {
			if (condition()) return true
			Thread.sleep(50)
		}
		return false
	}
}

/**
 * 블로킹 재시도를 전역으로 켠다. 우리 모듈의 정책 기반 backOffFunction 은 쓰지 않는다.
 * 곱셈이 일어나는지가 Spring Kafka 자체 동작인지 우리 배선 탓인지 가르기 위해서다.
 *
 * `backOff(FixedBackOff(50, 2))` = 재시도 2회 → 홉마다 로컬 3회 처리.
 */
@Configuration(proxyBeanMethods = false)
class MultiplyRetryTopicConfiguration : RetryTopicConfigurationSupport() {
	override fun configureBlockingRetries(blockingRetries: BlockingRetriesConfigurer) {
		blockingRetries
			.retryOn(IllegalStateException::class.java)
			.backOff(FixedBackOff(50L, 2L))
	}
}

@SpringBootApplication
class MultiplyApplication {
	@Bean fun multiplyRecorder() = MultiplyRecorder()
	@Bean fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })
}

private val log = LoggerFactory.getLogger("multiply")

/**
 * Spring 순정 `@RetryableTopic(attempts = "3")`.
 *
 * 논블로킹 3회(원본 + 재시도 토픽 2개)와 전역 블로킹 3회가 동시에 걸린 상태다.
 * 곱이 맞다면 리스너는 총 9회 호출된다.
 */
@Component
class MultiplyListener(private val recorder: MultiplyRecorder) {

	@RetryableTopic(attempts = "3", backoff = Backoff(delay = 100, multiplier = 2.0))
	@KafkaListener(id = "multiply-listener", topics = [TOPIC], groupId = "g-multiply")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.hops.add(topic)
		log.info("MULTIPLY consume topic={} 누적={}", topic, recorder.hops.size)
		throw IllegalStateException("항상 실패")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("MULTIPLY dlt topic={}", topic)
		recorder.hops.add(topic)
	}

	companion object {
		const val TOPIC = "multiply-orders"

		/** 백오프가 100ms → 200ms 로 갈리므로 재시도 토픽 이름에 지연 값이 붙는다. */
		const val RETRY_100 = "multiply-orders-retry-100"
		const val RETRY_200 = "multiply-orders-retry-200"
		const val DLT = "multiply-orders-dlt"

		/** `@RetryableTopic(attempts = "3")` → 원본 + 재시도 토픽 2개 */
		const val NON_BLOCKING_HOPS = 3

		/** 전역 `backOff(FixedBackOff(50, 2))` → 홉마다 로컬 3회 */
		const val BLOCKING_PER_HOP = 3
	}
}
