package com.study.kafka.retry.chain

import com.study.kafka.retry.CustomRetryAndDLT
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

/**
 * `@CustomRetryAndDLT`를 실제로 붙인 리스너.
 *
 * 페이로드가 `poison-`으로 시작하면 항상 실패시켜 재시도 체인을 끝까지 태운다.
 * 실패 예외를 [IllegalStateException]으로 두는 이유는 애노테이션 기본값에서
 * `exclude`가 비어 있어 모든 예외가 재시도 대상이기 때문이다.
 */
@Component
class OrderEventListener(
	private val recorder: RetryChainRecorder,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@CustomRetryAndDLT(owner = "order-team")
	@KafkaListener(topics = [TOPIC], groupId = "order-consumer")
	fun handle(
		payload: String,
		@Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
	) {
		recorder.record(topic, payload)
		log.info("consume topic={} payload={}", topic, payload)

		if (payload.startsWith("poison-")) {
			throw IllegalStateException("처리 불가 메시지: $payload")
		}
	}

	@DltHandler
	fun handleDlt(
		payload: String,
		@Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
	) {
		recorder.recordDlt(topic, payload)
		log.warn("DLT 적재 topic={} payload={}", topic, payload)
	}

	companion object {
		const val TOPIC = "orders"
	}
}
