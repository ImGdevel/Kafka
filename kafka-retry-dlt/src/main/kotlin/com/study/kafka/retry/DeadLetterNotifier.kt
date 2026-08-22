package com.study.kafka.retry

import org.slf4j.LoggerFactory

/**
 * `@DltHandler`에서 호출하는 진입점.
 *
 * 하는 일은 두 가지뿐이다.
 *  1. DLT 토픽으로 [CustomRetryDltPolicy]를 찾아 `owner`를 알아낸다.
 *  2. `alertOnDlt`가 꺼져 있으면 알림을 보내지 않는다.
 *
 * 즉 `@CustomRetryAndDLT`의 확장 속성이 실제로 소비되는 지점이다.
 */
class DeadLetterNotifier(
	private val registry: CustomRetryDltPolicyRegistry,
	private val alerter: DeadLetterAlerter,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	fun notifyDeadLetter(dltTopic: String, payload: Any?, reason: String? = null) {
		val policy = registry.findByTopic(dltTopic)
		if (policy == null) {
			log.warn("DLT 정책을 찾지 못했다. 알림을 건너뛴다. topic={}", dltTopic)
			return
		}
		if (!policy.alertOnDlt) {
			log.info("alertOnDlt=false 이므로 알림을 보내지 않는다. topic={} owner={}", dltTopic, policy.owner)
			return
		}
		alerter.alert(DeadLetterAlert(dltTopic, policy.owner, payload, reason, policy.listenerId))
	}
}
