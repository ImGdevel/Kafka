package com.study.kafka.retry.noannotation.withtx

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
 * 트랜잭션은 켰지만 CommonErrorHandler/AfterRollbackProcessor 빈은 등록하지 않은 앱.
 *
 * `spring.kafka.producer.transaction-id-prefix`만 프로퍼티로 설정하면 Boot가
 * `KafkaTransactionManager`를 만들고 리스너 컨테이너에 물린다.
 * `AbstractMessageListenerContainer` 생성자가 필드 기본값으로
 * `new DefaultAfterRollbackProcessor()`를 이미 박아 두므로, 이 앱에는 정말 아무 빈도 없다.
 */
@SpringBootApplication
class NoAnnotationTxApplication {
	@Bean
	fun noAnnotationTxRecorder() = NoAnnotationRecorder()
}

private val log = LoggerFactory.getLogger("no-annotation-tx")

@Component
class PlainTxListener(private val recorder: NoAnnotationRecorder) {

	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-plain-tx")
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		log.info("consume topic={} payload={} 누적={}", topic, payload, recorder.countListener(payload))
		throw IllegalStateException("항상 실패: $payload")
	}

	companion object {
		const val TOPIC = "plain-tx-orders"
		const val LISTENER_ID = "plain-tx-listener"
	}
}
