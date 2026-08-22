package com.study.kafka.retry.atomicity.commitfail

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.CustomRetryDltConfiguration
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.ConsumerPostProcessor
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 지정한 컨슈머 그룹의 오프셋 커밋만 골라서 실패시키는 게이트.
 *
 * DLT 핸들러는 별도 그룹이라 영향을 받지 않아야 한다. 전부 실패시키면
 * DLT 레코드까지 재전달돼 무엇 때문에 중복이 났는지 구분할 수 없다.
 */
class CommitGate(private val targetGroupId: String) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val failing = AtomicBoolean(false)

	val failedCommits = AtomicInteger()

	@Volatile
	var commitAttempted = CountDownLatch(1)
		private set

	fun startFailing() {
		failedCommits.set(0)
		commitAttempted = CountDownLatch(1)
		failing.set(true)
	}

	fun stopFailing() = failing.set(false)

	@Suppress("UNCHECKED_CAST")
	fun wrap(consumer: Consumer<Any, Any>): Consumer<Any, Any> {
		return Proxy.newProxyInstance(
			Consumer::class.java.classLoader,
			arrayOf(Consumer::class.java),
		) { _, method, args ->
			if (method.name.startsWith("commit") && shouldFail(consumer)) {
				failedCommits.incrementAndGet()
				commitAttempted.countDown()
				log.info("[CommitGate] {} 차단 group={}", method.name, targetGroupId)
				throw KafkaException("simulated commit failure")
			}
			try {
				method.invoke(consumer, *(args ?: emptyArray()))
			} catch (ex: InvocationTargetException) {
				throw ex.targetException
			}
		} as Consumer<Any, Any>
	}

	private fun shouldFail(consumer: Consumer<Any, Any>): Boolean =
		failing.get() && runCatching { consumer.groupMetadata().groupId() }.getOrNull() == targetGroupId
}

class CommitFailRecorder {
	val listenerDeliveries = ConcurrentLinkedQueue<String>()
	val dltDeliveries = ConcurrentLinkedQueue<String>()

	@Volatile
	var dltLatch = CountDownLatch(1)
		private set

	/** 앞으로 **추가로** 도착할 DLT 건수를 기다린다. 이미 도착한 건수는 세지 않는다. */
	fun expectMoreDlt(count: Int) {
		dltLatch = CountDownLatch(count)
	}

	fun recordDlt(payload: String) {
		dltDeliveries.add(payload)
		dltLatch.countDown()
	}
}

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class CommitFailApplication {

	@Bean
	fun commitFailRecorder() = CommitFailRecorder()

	@Bean
	fun commitGate() = CommitGate(CommitFailListener.GROUP)

	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })

	@Suppress("UNCHECKED_CAST")
	@Bean
	fun commitFailingConsumerCustomizer(gate: CommitGate) = DefaultKafkaConsumerFactoryCustomizer { factory ->
		(factory as DefaultKafkaConsumerFactory<Any, Any>)
			.addPostProcessor(ConsumerPostProcessor { consumer -> gate.wrap(consumer) })
	}
}

/**
 * attempts=1이라 재시도 토픽 없이 곧바로 DLT로 간다.
 * 중간 홉을 없애야 "DLT 발행 → 커밋" 한 구간만 관찰할 수 있다.
 */
@Component
class CommitFailListener(private val recorder: CommitFailRecorder) {

	private val log = LoggerFactory.getLogger(javaClass)

	@CustomRetryAndDLT(attempts = "1", owner = "atomicity-team")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		log.info("consume topic={} payload={} 누적={}", topic, payload, recorder.listenerDeliveries.size)
		throw IllegalStateException("항상 실패: $payload")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.recordDlt(payload)
	}

	companion object {
		const val TOPIC = "atom-commit"
		const val GROUP = "g-atom-commit"
		const val LISTENER_ID = "atom-commit-listener"
	}
}
