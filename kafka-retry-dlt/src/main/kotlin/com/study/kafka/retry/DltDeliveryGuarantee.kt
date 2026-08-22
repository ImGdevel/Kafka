package com.study.kafka.retry

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaOperations

/**
 * DLT 발행이 실제로 어떤 수준을 보장하는지.
 *
 * `@CustomRetryAndDLT`를 쓰는 쪽에서 가장 많이 착각하는 지점이라 값으로 만들어 드러낸다.
 */
enum class DltDeliveryGuarantee {

	/**
	 * 트랜잭션 없음. DLT 발행이 끝난 뒤 오프셋 커밋이 깨지면 재기동 시 같은 메시지가 한 번 더 쌓인다.
	 * 유실은 없지만 중복은 있다. 리스너와 DLT 핸들러가 멱등해야 한다.
	 */
	AT_LEAST_ONCE,

	/** 트랜잭션 프로듀서 + `read_committed` 컨슈머. 커밋이 깨지면 발행분도 abort돼 중복이 보이지 않는다. */
	EXACTLY_ONCE,

	/**
	 * 트랜잭션 프로듀서는 켰지만 컨슈머가 `read_committed`가 아니다.
	 *
	 * 발행 쪽은 원자적인데 소비 쪽이 abort된 레코드까지 읽으므로 EOS가 성립하지 않는다.
	 * 설정이 반쯤 되어 있어 겉보기에는 EOS 같지만 실제로는 아닌, 가장 위험한 상태다.
	 */
	BROKEN_EXACTLY_ONCE,
}

/** 프로듀서/컨슈머 설정 조합으로 보장 수준을 판정한다. 순수 함수라 브로커 없이 검증할 수 있다. */
object DltDeliveryGuarantees {

	const val READ_COMMITTED = "read_committed"

	fun evaluate(producerTransactional: Boolean, consumerIsolationLevel: String?): DltDeliveryGuarantee = when {
		!producerTransactional -> DltDeliveryGuarantee.AT_LEAST_ONCE
		consumerIsolationLevel?.trim()?.lowercase() == READ_COMMITTED -> DltDeliveryGuarantee.EXACTLY_ONCE
		else -> DltDeliveryGuarantee.BROKEN_EXACTLY_ONCE
	}
}

/**
 * 기동 시 DLT 발행 보장 수준을 판정해 로그로 남긴다.
 *
 * 특히 [DltDeliveryGuarantee.BROKEN_EXACTLY_ONCE]는 조용히 넘어가면 안 된다.
 * 트랜잭션을 켠 팀은 EOS라고 믿는데 컨슈머가 abort된 레코드를 읽어 중복이 그대로 발생한다.
 * 기동을 막지는 않는다. 트랜잭션을 EOS가 아닌 다른 목적으로 쓰는 구성도 있기 때문이다.
 */
class DltTransactionInspector(
	private val kafkaOperations: ObjectProvider<KafkaOperations<*, *>>,
	private val consumerFactory: ObjectProvider<ConsumerFactory<*, *>>,
) : SmartInitializingSingleton {

	private val log = LoggerFactory.getLogger(javaClass)

	@Volatile
	var guarantee: DltDeliveryGuarantee = DltDeliveryGuarantee.AT_LEAST_ONCE
		private set

	override fun afterSingletonsInstantiated() {
		val transactional = kafkaOperations.ifAvailable?.isTransactional ?: false
		val isolationLevel = consumerFactory.ifAvailable
			?.configurationProperties
			?.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG)
			?.toString()

		guarantee = DltDeliveryGuarantees.evaluate(transactional, isolationLevel)

		when (guarantee) {
			DltDeliveryGuarantee.EXACTLY_ONCE ->
				log.info("DLT 발행 보장 수준: EXACTLY_ONCE (트랜잭션 프로듀서 + read_committed)")

			DltDeliveryGuarantee.AT_LEAST_ONCE ->
				log.info(
					"DLT 발행 보장 수준: AT_LEAST_ONCE. 오프셋 커밋 실패 시 DLT에 중복이 쌓일 수 있다. " +
						"EOS가 필요하면 spring.kafka.producer.transaction-id-prefix 와 " +
						"spring.kafka.consumer.isolation-level=read-committed 를 함께 설정해라.",
				)

			DltDeliveryGuarantee.BROKEN_EXACTLY_ONCE ->
				log.warn(
					"DLT 발행 보장 수준: BROKEN_EXACTLY_ONCE. 트랜잭션 프로듀서는 켜져 있지만 " +
						"컨슈머 isolation.level 이 '{}' 이라 abort 된 레코드까지 읽는다. " +
						"spring.kafka.consumer.isolation-level=read-committed 를 설정해야 EOS가 성립한다.",
					isolationLevel ?: "read_uncommitted(기본값)",
				)
		}
	}
}
