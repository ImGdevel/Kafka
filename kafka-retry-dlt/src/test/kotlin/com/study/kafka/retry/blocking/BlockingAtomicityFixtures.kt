package com.study.kafka.retry.blocking

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.CustomRetryDltConfiguration
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.KafkaException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.core.ProducerPostProcessor
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.support.SendResult
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BlockingRecorder {

	val listenerDeliveries = ConcurrentLinkedQueue<String>()
	val dltDeliveries = ConcurrentLinkedQueue<String>()

	fun countListener(payload: String) = listenerDeliveries.count { it == payload }

	fun countDlt(payload: String) = dltDeliveries.count { it == payload }

	fun awaitDlt(payload: String, seconds: Long) = awaitUntil(seconds) { countDlt(payload) > 0 }

	fun awaitListener(payload: String, atLeast: Int, seconds: Long) =
		awaitUntil(seconds) { countListener(payload) >= atLeast }

	private fun awaitUntil(seconds: Long, condition: () -> Boolean): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
		while (System.nanoTime() < deadline) {
			if (condition()) return true
			Thread.sleep(50)
		}
		return false
	}
}

/**
 * 지정한 컨슈머 그룹의 트랜잭션 커밋을 막는다. "발행은 끝났는데 커밋 직전에 죽는" 상황을 만드는 레버다.
 *
 * EOS 모드에서 프로듀서는 풀에서 공유되므로 인스턴스로는 어느 리스너의 트랜잭션인지 알 수 없다.
 * 커밋 직전에 오프셋과 함께 넘어오는 컨슈머 그룹을 스레드 로컬에 담아 판별한다.
 * 다른 리스너나 DLT 핸들러의 트랜잭션까지 깨면 결과의 원인을 구분할 수 없다.
 */
class CommitFailureGate {

	private val log = LoggerFactory.getLogger(javaClass)
	private val failing = AtomicBoolean(false)
	private val currentGroup = ThreadLocal<String?>()

	@Volatile
	var targetGroupId: String? = null
		private set

	/** 브로커까지 실제로 나간 DLT 발행 횟수. abort 된 것도 센다. */
	val dltSendAttempts = AtomicInteger()
	val failedCommits = AtomicInteger()

	@Volatile
	private var blocked = CountDownLatch(1)

	fun startFailing(groupId: String) {
		targetGroupId = groupId
		blocked = CountDownLatch(1)
		failing.set(true)
	}

	fun stopFailing() = failing.set(false)

	fun awaitBlockedCommit(seconds: Long): Boolean = blocked.await(seconds, TimeUnit.SECONDS)

	@Suppress("UNCHECKED_CAST")
	fun wrap(producer: Producer<Any, Any>): Producer<Any, Any> =
		Proxy.newProxyInstance(
			Producer::class.java.classLoader,
			arrayOf(Producer::class.java),
		) { _, method, args ->
			when (method.name) {
				"beginTransaction" -> currentGroup.remove()

				"send" -> (args?.getOrNull(0) as? ProducerRecord<*, *>)
					?.takeIf { it.topic().endsWith(".dlt") }
					?.let { dltSendAttempts.incrementAndGet() }

				"sendOffsetsToTransaction" -> currentGroup.set(
					(args?.getOrNull(1) as? ConsumerGroupMetadata)?.groupId(),
				)

				"commitTransaction" -> if (shouldFailCommit()) {
					failedCommits.incrementAndGet()
					blocked.countDown()
					log.info("[CommitFailureGate] commitTransaction 차단 group={}", targetGroupId)
					throw KafkaException("simulated transaction commit failure")
				}
			}
			try {
				method.invoke(producer, *(args ?: emptyArray()))
			} catch (ex: InvocationTargetException) {
				throw ex.targetException
			}
		} as Producer<Any, Any>

	private fun shouldFailCommit(): Boolean =
		failing.get() && currentGroup.get() != null && currentGroup.get() == targetGroupId
}

/** 지정한 DLT 토픽으로 나가는 발행만 실패시킨다. "DLT 발행 자체가 깨지는" 상황을 만드는 레버다. */
class SendFailureGate(private val dltTopic: String) {

	private val failing = AtomicBoolean(true)

	val attempts = AtomicInteger()

	fun stopFailing() = failing.set(false)

	fun shouldFail(topic: String): Boolean {
		if (topic != dltTopic) return false
		attempts.incrementAndGet()
		return failing.get()
	}
}

class FailingDltKafkaTemplate(
	producerFactory: ProducerFactory<Any, Any>,
	private val gate: SendFailureGate,
) : KafkaTemplate<Any, Any>(producerFactory) {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun send(record: ProducerRecord<Any, Any>): CompletableFuture<SendResult<Any, Any>> {
		if (gate.shouldFail(record.topic())) {
			log.info("[SendFailureGate] DLT 발행 차단 topic={}", record.topic())
			return CompletableFuture.failedFuture(RuntimeException("simulated DLT send failure"))
		}
		return super.send(record)
	}
}

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class BlockingAtomicityApplication {

	@Bean
	fun blockingRecorder() = BlockingRecorder()

	@Bean
	fun commitFailureGate() = CommitFailureGate()

	@Bean
	fun sendFailureGate() = SendFailureGate(SendFailListener.DLT_TOPIC)

	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })

	@Suppress("UNCHECKED_CAST")
	@Bean
	fun gatedProducerCustomizer(gate: CommitFailureGate) = DefaultKafkaProducerFactoryCustomizer { factory ->
		(factory as DefaultKafkaProducerFactory<Any, Any>)
			.addPostProcessor(ProducerPostProcessor { producer -> gate.wrap(producer) })
	}

	/**
	 * KafkaTemplate 빈을 하나라도 직접 정의하면 Boot의 `@ConditionalOnMissingBean`이 물러나
	 * 자동설정 템플릿이 사라진다. 그래서 정상 템플릿도 여기서 만들고 `@Primary`로 둔다.
	 */
	@Bean
	@Primary
	fun kafkaTemplate(producerFactory: ProducerFactory<Any, Any>) = KafkaTemplate(producerFactory)

	@Bean
	fun failingDltKafkaTemplate(producerFactory: ProducerFactory<Any, Any>, gate: SendFailureGate) =
		FailingDltKafkaTemplate(producerFactory, gate)
}

private val log = LoggerFactory.getLogger("blocking-atomicity")

private fun alwaysFail(payload: String, topic: String, recorder: BlockingRecorder): Nothing {
	recorder.listenerDeliveries.add(payload)
	log.info("consume topic={} payload={} 누적={}", topic, payload, recorder.countListener(payload))
	throw IllegalStateException("항상 실패: $payload")
}

private fun recordDlt(payload: String, topic: String, recorder: BlockingRecorder) {
	log.info("DLT 수신 topic={} payload={}", topic, payload)
	recorder.dltDeliveries.add(payload)
}

/**
 * 블로킹 재시도만으로 DLT까지 가는 리스너. `attempts=1`이라 재시도 토픽은 만들어지지 않는다.
 *
 * 블로킹 횟수를 12로 크게 잡아 둔 이유가 있다. 트랜잭션 컨테이너에서는 소진 전 시도마다 롤백이 일어나고,
 * 컨테이너 기본 롤백 처리기가 자기 카운터(10회)를 따로 센다. 그쪽이 먼저 소진되면 로그에
 * `Backoff ... exhausted`가 찍히는데, 그걸 보고 레코드가 버려졌다고 오해하기 쉽다.
 * 실제로는 설정한 횟수를 끝까지 채우고 DLT까지 간다는 것을 횟수로 고정한다.
 */
@Component
class BlockingOnlyListener(private val recorder: BlockingRecorder) {

	@CustomRetryAndDLT(attempts = "1", blockingAttempts = "12", blockingBackoffDelay = "10")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit =
		alwaysFail(payload, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) =
		recordDlt(payload, topic, recorder)

	companion object {
		const val TOPIC = "blk-plain"
		const val GROUP = "g-blk-plain"
		const val LISTENER_ID = "blk-plain-listener"
		const val BLOCKING_ATTEMPTS = 12
	}
}

/** 마지막 시도의 오프셋 커밋을 깨뜨려 원자성을 확인할 리스너. */
@Component
class CommitFailListener(private val recorder: BlockingRecorder) {

	@CustomRetryAndDLT(attempts = "1", blockingAttempts = "3", blockingBackoffDelay = "50")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit =
		alwaysFail(payload, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) =
		recordDlt(payload, topic, recorder)

	companion object {
		const val TOPIC = "blk-commit"
		const val GROUP = "g-blk-commit"
		const val LISTENER_ID = "blk-commit-listener"
		const val BLOCKING_ATTEMPTS = 3
	}
}

/** DLT 발행 자체를 실패시킬 리스너. 전용 KafkaTemplate을 물린다. */
@Component
class SendFailListener(private val recorder: BlockingRecorder) {

	@CustomRetryAndDLT(
		attempts = "1",
		blockingAttempts = "3",
		blockingBackoffDelay = "50",
		kafkaTemplate = "failingDltKafkaTemplate",
	)
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String): Unit =
		alwaysFail(payload, topic, recorder)

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) =
		recordDlt(payload, topic, recorder)

	companion object {
		const val TOPIC = "blk-send"
		const val DLT_TOPIC = "blk-send.dlt"
		const val GROUP = "g-blk-send"
		const val LISTENER_ID = "blk-send-listener"
		const val BLOCKING_ATTEMPTS = 3
	}
}
