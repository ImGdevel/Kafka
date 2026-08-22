package com.study.kafka.retry.chain

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.DeadLetterNotifier
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

/**
 * `@CustomRetryAndDLT`를 실제로 붙인 리스너. 알림을 켠 쪽(`alertOnDlt` 기본값 true).
 *
 * 페이로드가 `poison-`으로 시작하면 항상 실패시켜 재시도 체인을 끝까지 태운다.
 */
@Component
class OrderEventListener(
	private val recorder: RetryChainRecorder,
	private val notifier: DeadLetterNotifier,
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

	/**
	 * `owner`/`alertOnDlt`는 여기서 소비된다.
	 * 핸들러는 토픽 이름만 넘기고, 담당자 조회와 알림 여부 판단은 [DeadLetterNotifier]가 정책에서 읽는다.
	 */
	@DltHandler
	fun handleDlt(
		payload: String,
		@Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
		@Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) reason: String?,
	) {
		recorder.record(topic, payload)
		notifier.notifyDeadLetter(topic, payload, reason)
		recorder.dltProcessed()
	}

	companion object {
		const val TOPIC = "orders"
	}
}
