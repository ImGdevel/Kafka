package com.study.kafka.retry.chain

import com.study.kafka.retry.DeadLetterAlert
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * 재시도 체인이 어떤 토픽을 거쳐 갔는지, 어떤 DLT 알림이 나갔는지 기록한다.
 *
 * 리스너/DLT 핸들러는 별도 컨테이너 스레드에서 돌기 때문에 스레드 안전한 컬렉션과 래치가 필요하다.
 */
class RetryChainRecorder {

	data class Delivery(val topic: String, val payload: String)

	private val deliveries = ConcurrentLinkedQueue<Delivery>()
	private val alerts = ConcurrentLinkedQueue<DeadLetterAlert>()

	@Volatile
	var dltLatch = CountDownLatch(1)
		private set

	fun record(topic: String, payload: String) {
		deliveries.add(Delivery(topic, payload))
	}

	fun recordAlert(alert: DeadLetterAlert) {
		alerts.add(alert)
	}

	/** DLT 핸들러가 알림 처리까지 끝냈음을 알린다. 알림 기록 뒤에 호출해야 테스트가 경합하지 않는다. */
	fun dltProcessed() = dltLatch.countDown()

	fun topicsFor(payload: String): List<String> =
		deliveries.filter { it.payload == payload }.map { it.topic }

	fun alertsFor(payload: String): List<DeadLetterAlert> =
		alerts.filter { it.payload == payload }

	fun allAlerts(): List<DeadLetterAlert> = alerts.toList()

	fun reset() {
		deliveries.clear()
		alerts.clear()
		dltLatch = CountDownLatch(1)
	}
}
