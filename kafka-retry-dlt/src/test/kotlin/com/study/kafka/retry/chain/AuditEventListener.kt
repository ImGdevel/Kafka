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
 * 알림을 끈 리스너(`alertOnDlt = false`).
 *
 * 코드는 [OrderEventListener]와 동일하게 [DeadLetterNotifier]를 부르지만,
 * 정책이 `alertOnDlt = false`이므로 알림은 나가지 않아야 한다.
 * 즉 알림 억제 판단이 리스너 코드가 아니라 애노테이션 속성에서 온다는 것을 보여준다.
 */
@Component
class AuditEventListener(
	private val recorder: RetryChainRecorder,
	private val notifier: DeadLetterNotifier,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@CustomRetryAndDLT(attempts = "2", owner = "audit-team", alertOnDlt = false)
	@KafkaListener(topics = [TOPIC], groupId = "audit-consumer")
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
		@Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) reason: String?,
	) {
		recorder.record(topic, payload)
		notifier.notifyDeadLetter(topic, payload, reason)
		recorder.dltProcessed()
	}

	companion object {
		const val TOPIC = "audits"
	}
}
