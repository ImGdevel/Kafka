package com.study.kafka.retry.eos

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
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.ProducerPostProcessor
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 프로듀서를 감싸 DLT 발행 횟수를 세고, 지정한 컨슈머 그룹의 트랜잭션 커밋만 실패시킨다.
 *
 * EOS 모드에서 프로듀서는 풀에서 공유되므로 인스턴스로는 어떤 리스너의 트랜잭션인지 구분할 수 없다.
 * 대신 `sendOffsetsToTransaction`이 넘겨주는 컨슈머 그룹을 스레드 로컬에 담아두고
 * 바로 뒤따르는 `commitTransaction`이 그 그룹의 것인지 판단한다.
 * DLT 핸들러의 트랜잭션까지 같이 깨면 무엇 때문에 결과가 달라졌는지 구분할 수 없다.
 */
class ProducerGate {

	/** 어떤 리스너의 트랜잭션을 깨뜨릴지. 테스트마다 바꿔 끼운다. */
	@Volatile
	var targetGroupId: String? = null

	private val log = LoggerFactory.getLogger(javaClass)
	private val failing = AtomicBoolean(false)
	private val currentGroup = ThreadLocal<String?>()

	/** 브로커로 실제 나간 DLT 발행 횟수. abort된 것도 포함한다. */
	val dltSendAttempts = AtomicInteger()
	val failedCommits = AtomicInteger()

	/** 트랜잭션 경계 카운터. 블로킹 재시도가 한 트랜잭션 안에서 도는지 판별하는 데 쓴다. */
	val beginCount = AtomicInteger()
	val commitCount = AtomicInteger()
	val abortCount = AtomicInteger()

	fun snapshot() = Snapshot(beginCount.get(), commitCount.get(), abortCount.get(), dltSendAttempts.get())

	data class Snapshot(val begins: Int, val commits: Int, val aborts: Int, val dltSends: Int) {
		operator fun minus(other: Snapshot) =
			Snapshot(begins - other.begins, commits - other.commits, aborts - other.aborts, dltSends - other.dltSends)
	}

	@Volatile
	private var commitBlocked = CountDownLatch(1)

	fun startFailing() {
		commitBlocked = CountDownLatch(1)
		failing.set(true)
	}

	fun stopFailing() = failing.set(false)

	fun awaitBlockedCommit(seconds: Long): Boolean = commitBlocked.await(seconds, TimeUnit.SECONDS)

	@Suppress("UNCHECKED_CAST")
	fun wrap(producer: Producer<Any, Any>): Producer<Any, Any> =
		Proxy.newProxyInstance(
			Producer::class.java.classLoader,
			arrayOf(Producer::class.java),
		) { _, method, args ->
			when (method.name) {
				"beginTransaction" -> {
					currentGroup.remove()
					beginCount.incrementAndGet()
				}

				"abortTransaction" -> abortCount.incrementAndGet()
				"send" -> (args?.getOrNull(0) as? ProducerRecord<*, *>)
					?.takeIf { it.topic().endsWith("-dlt") }
					?.let { dltSendAttempts.incrementAndGet() }

				"sendOffsetsToTransaction" -> currentGroup.set(
					(args?.getOrNull(1) as? ConsumerGroupMetadata)?.groupId(),
				)

				"commitTransaction" -> {
					commitCount.incrementAndGet()
					if (failing.get() && currentGroup.get() != null && currentGroup.get() == targetGroupId) {
						failedCommits.incrementAndGet()
						commitBlocked.countDown()
						log.info("[ProducerGate] commitTransaction 차단 group={}", targetGroupId)
						throw KafkaException("simulated transaction commit failure")
					}
				}
			}
			try {
				method.invoke(producer, *(args ?: emptyArray()))
			} catch (ex: InvocationTargetException) {
				throw ex.targetException
			}
		} as Producer<Any, Any>
}

class EosRecorder {
	val listenerDeliveries = ConcurrentLinkedQueue<String>()
	val dltDeliveries = ConcurrentLinkedQueue<String>()

	/** 체인 검증용. (페이로드, 토픽)을 순서대로 남긴다. 테스트가 컨텍스트를 공유하므로 페이로드로 갈라야 한다. */
	private val hops = ConcurrentLinkedQueue<Pair<String, String>>()

	fun recordHop(payload: String, topic: String) {
		hops.add(payload to topic)
	}

	fun hopsFor(payload: String): List<String> = hops.filter { it.first == payload }.map { it.second }

	fun countListener(payload: String) = listenerDeliveries.count { it == payload }
	fun countDlt(payload: String) = dltDeliveries.count { it == payload }

	/** 폴링으로 기다린다. 페이로드별 래치를 두는 것보다 테스트가 서로 간섭하지 않는다. */
	fun awaitDlt(payload: String, seconds: Long): Boolean {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
		while (System.nanoTime() < deadline) {
			if (countDlt(payload) > 0) return true
			Thread.sleep(50)
		}
		return false
	}
}

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class EosApplication {

	@Bean
	fun eosRecorder() = EosRecorder()

	@Bean
	fun producerGate() = ProducerGate()

	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })

	@Suppress("UNCHECKED_CAST")
	@Bean
	fun gatedProducerCustomizer(gate: ProducerGate) = DefaultKafkaProducerFactoryCustomizer { factory ->
		(factory as DefaultKafkaProducerFactory<Any, Any>)
			.addPostProcessor(ProducerPostProcessor { producer -> gate.wrap(producer) })
	}
}

/** attempts=1이라 재시도 토픽 없이 곧장 DLT로 간다. 관찰 구간을 "발행 + 오프셋 커밋" 하나로 좁힌다. */
@Component
class EosListener(private val recorder: EosRecorder) {

	private val log = LoggerFactory.getLogger(javaClass)

	@CustomRetryAndDLT(attempts = "1", owner = "eos-team")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		log.info("consume topic={} payload={} 누적={}", topic, payload, recorder.countListener(payload))
		throw IllegalStateException("항상 실패: $payload")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.dltDeliveries.add(payload)
	}

	companion object {
		const val TOPIC = "eos-orders"
		const val GROUP = "g-eos-orders"
		const val LISTENER_ID = "eos-listener"
	}
}

/** 블로킹 재시도를 켠 리스너. 트랜잭션과 함께 쓸 때 트랜잭션 경계가 어떻게 잡히는지 본다. */
@Component
class EosBlockingListener(private val recorder: EosRecorder) {

	private val log = LoggerFactory.getLogger(javaClass)

	@CustomRetryAndDLT(attempts = "1", blockingAttempts = "3", blockingBackoffDelay = "100", owner = "eos-team")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		log.info("consume topic={} payload={} 누적={}", topic, payload, recorder.countListener(payload))
		throw IllegalStateException("항상 실패: $payload")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.dltDeliveries.add(payload)
	}

	companion object {
		const val TOPIC = "eos-blocking"
		const val GROUP = "g-eos-blocking"
		const val LISTENER_ID = "eos-blocking-listener"
	}
}

/** 재시도 토픽이 여러 개인 체인. 홉마다 트랜잭션이 걸리는지 본다. */
@Component
class EosChainListener(private val recorder: EosRecorder) {

	private val log = LoggerFactory.getLogger(javaClass)

	@CustomRetryAndDLT(attempts = "3", owner = "eos-team")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.recordHop(payload, topic)
		recorder.listenerDeliveries.add(payload)
		log.info("consume topic={} payload={}", topic, payload)
		throw IllegalStateException("항상 실패: $payload")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.recordHop(payload, topic)
		recorder.dltDeliveries.add(payload)
	}

	companion object {
		const val TOPIC = "eos-chain"
		const val GROUP = "g-eos-chain"
		const val LISTENER_ID = "eos-chain-listener"
	}
}
