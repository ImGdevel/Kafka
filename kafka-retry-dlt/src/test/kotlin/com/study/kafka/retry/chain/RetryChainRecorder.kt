package com.study.kafka.retry.chain

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * 재시도 체인이 어떤 토픽을 거쳐 갔는지 기록한다.
 *
 * 리스너/DLT 핸들러는 별도 컨테이너 스레드에서 돌기 때문에 스레드 안전한 컬렉션과 래치가 필요하다.
 */
class RetryChainRecorder {

	data class Delivery(val topic: String, val payload: String)

	private val deliveries = ConcurrentLinkedQueue<Delivery>()

	@Volatile
	var dltLatch = CountDownLatch(1)
		private set

	fun record(topic: String, payload: String) {
		deliveries.add(Delivery(topic, payload))
	}

	fun recordDlt(topic: String, payload: String) {
		record(topic, payload)
		dltLatch.countDown()
	}

	fun topicsFor(payload: String): List<String> =
		deliveries.filter { it.payload == payload }.map { it.topic }

	fun reset() {
		deliveries.clear()
		dltLatch = CountDownLatch(1)
	}
}
