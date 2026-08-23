package com.study.kafka.retry.noannotation.notx

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class NoAnnotationRecorder {

	val listenerDeliveries = ConcurrentLinkedQueue<String>()

	fun countListener(payload: String) = listenerDeliveries.count { it == payload }

	/** N회 도달 후 "더 이상 늘지 않고 멈췄는지"까지 보려고, 도달 시각과 그 뒤 정체 여부를 함께 확인한다. */
	fun awaitCount(payload: String, target: Int, seconds: Long): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
		while (System.nanoTime() < deadline) {
			if (countListener(payload) >= target) return true
			Thread.sleep(50)
		}
		return false
	}
}

/**
 * @CustomRetryAndDLT 도, 다른 어떤 재시도 설정도 없는 순수 @KafkaListener.
 *
 * CustomRetryDltConfiguration 을 아예 들여오지 않는다. 전역 CommonErrorHandler 빈도 없다.
 * 이 앱에는 재시도/DLT 관련 빈이 하나도 없어야 "빈이 전혀 없을 때" 컨테이너가 무엇을 즉석 생성하는지
 * 순수하게 관찰할 수 있다.
 */
@SpringBootApplication
class NoAnnotationApplication {
	@Bean
	fun noAnnotationRecorder() = NoAnnotationRecorder()
}

private val log = LoggerFactory.getLogger("no-annotation")

@Component
class PlainListener(private val recorder: NoAnnotationRecorder) {

	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-plain")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		log.info("consume topic={} payload={} 누적={}", topic, payload, recorder.countListener(payload))
		throw IllegalStateException("항상 실패: $payload")
	}

	companion object {
		const val TOPIC = "plain-orders"
		const val LISTENER_ID = "plain-listener"
	}
}
