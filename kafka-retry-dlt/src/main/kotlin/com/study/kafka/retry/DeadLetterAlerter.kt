package com.study.kafka.retry

import org.slf4j.LoggerFactory

/**
 * DLT 적재 알림 한 건.
 *
 * @param dltTopic 메시지가 최종 적재된 DLT 토픽
 * @param owner `@CustomRetryAndDLT(owner = ...)`로 지정한 담당자
 * @param payload 원본 페이로드
 * @param reason 마지막 실패 예외 메시지(헤더에 없으면 null)
 * @param listenerId 정책이 유래한 리스너 식별자
 */
data class DeadLetterAlert(
	val dltTopic: String,
	val owner: String,
	val payload: Any?,
	val reason: String?,
	val listenerId: String,
)

/** DLT 알림 전송 전략. Slack/PagerDuty 등으로 갈아 끼우는 지점. */
fun interface DeadLetterAlerter {
	fun alert(alert: DeadLetterAlert)
}

/** 별도 알림 채널이 없을 때 쓰는 기본 구현. ERROR 레벨로 남겨 로그 기반 경보에 걸리게 한다. */
class LoggingDeadLetterAlerter : DeadLetterAlerter {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun alert(alert: DeadLetterAlert) {
		log.error(
			"[DLT-ALERT] owner={} listener={} topic={} reason={} payload={}",
			alert.owner.ifBlank { "unassigned" },
			alert.listenerId,
			alert.dltTopic,
			alert.reason ?: "unknown",
			alert.payload,
		)
	}
}
