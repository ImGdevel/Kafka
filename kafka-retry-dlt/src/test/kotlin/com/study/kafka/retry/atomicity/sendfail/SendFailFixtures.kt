package com.study.kafka.retry.atomicity.sendfail

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.CustomRetryDltConfiguration
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.support.SendResult
import org.springframework.messaging.handler.annotation.Header
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** DLT 토픽으로 나가는 발행만 실패시키는 게이트. 테스트가 원본 토픽으로 넣는 메시지는 건드리지 않는다. */
class SendGate {
	private val failing = AtomicBoolean(true)
	val attemptedDltSends = AtomicInteger()

	fun stopFailing() = failing.set(false)

	fun shouldFail(topic: String): Boolean {
		if (!topic.endsWith(".dlt")) return false
		attemptedDltSends.incrementAndGet()
		return failing.get()
	}
}

class FailingKafkaTemplate(
	producerFactory: ProducerFactory<Any, Any>,
	private val gate: SendGate,
) : KafkaTemplate<Any, Any>(producerFactory) {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun send(record: ProducerRecord<Any, Any>): CompletableFuture<SendResult<Any, Any>> {
		if (gate.shouldFail(record.topic())) {
			log.info("[SendGate] DLT 발행 차단 topic={}", record.topic())
			return CompletableFuture.failedFuture(RuntimeException("simulated DLT send failure"))
		}
		return super.send(record)
	}
}

class SendFailRecorder {
	val listenerDeliveries = ConcurrentLinkedQueue<String>()
	val dltDeliveries = ConcurrentLinkedQueue<String>()

	val redeliveryLatch = CountDownLatch(3)
	val dltLatch = CountDownLatch(1)
}

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class SendFailApplication {

	@Bean
	fun sendFailRecorder() = SendFailRecorder()

	@Bean
	fun sendGate() = SendGate()

	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })

	/**
	 * KafkaTemplate 빈을 하나라도 직접 정의하면 Boot의 @ConditionalOnMissingBean이 물러나
	 * 자동설정 템플릿이 사라진다. 그래서 정상 템플릿도 여기서 같이 만들고 @Primary로 둔다.
	 */
	@Bean
	@Primary
	fun kafkaTemplate(producerFactory: ProducerFactory<Any, Any>) = KafkaTemplate(producerFactory)

	@Bean
	fun failingKafkaTemplate(producerFactory: ProducerFactory<Any, Any>, gate: SendGate) =
		FailingKafkaTemplate(producerFactory, gate)
}

/**
 * DLT 발행에만 실패하는 템플릿을 물린 리스너.
 *
 * attempts=1이라 재시도 토픽 없이 곧장 DLT로 간다. 그 발행이 실패하면 오프셋이 커밋되지 않아
 * 같은 레코드가 계속 재전달된다. 리스너가 매번 잠깐 자는 것은 재전달 루프가
 * 테스트 로그를 뒤덮지 않게 속도를 늦추기 위한 것이다.
 */
@Component
class SendFailListener(private val recorder: SendFailRecorder) {

	private val log = LoggerFactory.getLogger(javaClass)

	@CustomRetryAndDLT(attempts = "1", kafkaTemplate = "failingKafkaTemplate", owner = "atomicity-team")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = GROUP)
	fun handle(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		recorder.listenerDeliveries.add(payload)
		recorder.redeliveryLatch.countDown()
		log.info("consume topic={} payload={} 누적={}", topic, payload, recorder.listenerDeliveries.size)
		Thread.sleep(100)
		throw IllegalStateException("항상 실패: $payload")
	}

	@DltHandler
	fun dlt(payload: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
		log.info("DLT 수신 topic={} payload={}", topic, payload)
		recorder.dltDeliveries.add(payload)
		recorder.dltLatch.countDown()
	}

	companion object {
		const val TOPIC = "atom-send"
		const val GROUP = "g-atom-send"
		const val LISTENER_ID = "atom-send-listener"
	}
}
