package com.study.kafka.retry.eos

import com.study.kafka.retry.DltDeliveryGuarantee
import com.study.kafka.retry.DltTransactionInspector
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.transaction.KafkaAwareTransactionManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kafka 트랜잭션을 켜서 DLT 발행과 오프셋 커밋을 원자적으로 묶는다.
 *
 * 비트랜잭션 구성에서는 발행이 끝난 뒤 커밋이 깨지면 재기동 시 DLT에 같은 메시지가
 * 한 번 더 쌓인다(`DltPublishThenCommitFailTest` 참고). 트랜잭션을 켜면 발행 레코드와
 * 오프셋이 한 트랜잭션에 들어가므로 커밋이 깨질 때 발행분도 함께 abort된다.
 * 컨슈머가 `read_committed`면 abort된 레코드는 아예 보이지 않는다.
 *
 * 필요한 설정 세 가지
 *  - `spring.kafka.producer.transaction-id-prefix` → 트랜잭션 프로듀서와 KafkaTransactionManager
 *  - `spring.kafka.consumer.isolation-level=read-committed` → abort된 레코드를 걸러냄
 *  - 위 둘이 갖춰지면 Boot가 리스너 컨테이너에 트랜잭션 매니저를 물려준다
 *
 * `isolation-level`이 빠지면 발행은 원자적이어도 소비 쪽에서 abort된 레코드를 읽어
 * EOS가 깨진다. 그래서 이 테스트는 그 설정까지 함께 명시한다.
 */
@SpringBootTest(
	classes = [EosApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"spring.kafka.consumer.isolation-level=read-committed",
		"spring.kafka.producer.transaction-id-prefix=eos-tx-",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(
	partitions = 1,
	topics = [EosListener.TOPIC, EosBlockingListener.TOPIC, EosChainListener.TOPIC],
)
@DisplayName("DLT 발행 EOS")
class DltPublishEosTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

	@Autowired
	private lateinit var recorder: EosRecorder

	@Autowired
	private lateinit var producerGate: ProducerGate

	@Autowired
	private lateinit var txManagers: ObjectProvider<KafkaAwareTransactionManager<*, *>>

	@Autowired
	private lateinit var inspector: DltTransactionInspector

	@Test
	@DisplayName("트랜잭션을 켜도 재시도/DLT 라우팅은 그대로 동작한다")
	fun `retry topic infrastructure works under transactions`() {
		assertEquals(1L, txManagers.stream().count(), "KafkaTransactionManager 빈이 없다")
		assertTrue(kafkaTemplate.isTransactional, "KafkaTemplate이 트랜잭션 모드가 아니다")
		// 설정이 반만 되어 있으면 여기서 BROKEN_EXACTLY_ONCE가 나온다.
		assertEquals(DltDeliveryGuarantee.EXACTLY_ONCE, inspector.guarantee)

		kafkaTemplate.executeInTransaction { it.send(EosListener.TOPIC, "tx-routing") }

		assertTrue(recorder.awaitDlt("tx-routing", 30), "트랜잭션 컨테이너에서 DLT까지 도달하지 못했다")
		assertEquals(1, recorder.countListener("tx-routing"))
		assertEquals(1, recorder.countDlt("tx-routing"))
	}

	@Test
	@DisplayName("트랜잭션 커밋이 깨지면 발행분도 abort돼 DLT 중복이 생기지 않는다")
	fun `aborted transaction does not leak a duplicate dlt record`() {
		producerGate.targetGroupId = EosListener.GROUP
		producerGate.startFailing()
		kafkaTemplate.executeInTransaction { it.send(EosListener.TOPIC, "eos-poison") }

		// 1) 커밋이 막힌 트랜잭션 안에서 DLT 발행 자체는 브로커까지 나갔다.
		assertTrue(producerGate.awaitBlockedCommit(30), "커밋 차단이 관측되지 않았다")
		assertTrue(producerGate.failedCommits.get() > 0, "트랜잭션 커밋이 실제로 실패하지 않았다")
		assertTrue(producerGate.dltSendAttempts.get() > 0, "DLT 발행 시도가 없었다")

		// 2) 차단을 풀면 롤백된 레코드가 다시 처리돼 이번엔 정상 커밋된다.
		producerGate.stopFailing()
		assertTrue(recorder.awaitDlt("eos-poison", 30), "복구 후에도 DLT 적재가 없었다")

		// 3) 핵심: 발행은 두 번 이상 나갔지만 read_committed 컨슈머는 한 번만 본다.
		Thread.sleep(3_000) // 중복이 뒤늦게 올라오는지 확인할 여유
		assertTrue(
			producerGate.dltSendAttempts.get() >= 2,
			"발행이 한 번뿐이면 abort/재발행 시나리오가 재현되지 않은 것이다 → ${producerGate.dltSendAttempts.get()}",
		)
		assertEquals(
			1,
			recorder.countDlt("eos-poison"),
			"abort된 DLT 레코드가 소비됐다. EOS가 깨졌다",
		)
		assertTrue(
			recorder.countListener("eos-poison") >= 2,
			"롤백 후 재처리가 일어나지 않았다 → ${recorder.countListener("eos-poison")}",
		)
	}

	@Test
	@DisplayName("블로킹 재시도는 시도마다 트랜잭션을 새로 열고, DLT는 한 번만 쌓인다")
	fun `blocking retries run in separate transactions`() {
		val before = producerGate.snapshot()

		kafkaTemplate.executeInTransaction { it.send(EosBlockingListener.TOPIC, "blocking-tx") }
		assertTrue(recorder.awaitDlt("blocking-tx", 30), "블로킹 재시도 뒤 DLT까지 가지 못했다")
		Thread.sleep(2_000)

		val delta = producerGate.snapshot() - before
		println("EOS-BLOCKING-TX $delta listenerCalls=${recorder.countListener("blocking-tx")}")

		// blockingAttempts=3 → 로컬 3회 처리 후 DLT
		assertEquals(3, recorder.countListener("blocking-tx"))
		assertEquals(1, recorder.countDlt("blocking-tx"))

		// 트랜잭션이 백오프 동안 열린 채 유지되면 begin이 1회여야 한다.
		// 실제로는 시도마다 롤백 후 재폴링이라 begin이 시도 횟수만큼 늘어난다.
		// 즉 transaction.timeout.ms 는 개별 시도 시간만 덮고, 전체 블로킹 구간을 덮지 않는다.
		assertTrue(
			delta.begins >= 3,
			"블로킹 재시도가 한 트랜잭션 안에서 돈 것으로 보인다 → begins=${delta.begins}",
		)
		assertTrue(delta.aborts >= 2, "실패한 시도마다 abort가 있어야 한다 → aborts=${delta.aborts}")
	}

	@Test
	@DisplayName("재시도 토픽이 여러 개인 체인도 트랜잭션에서 그대로 흐른다")
	fun `multi hop retry chain works under transactions`() {
		kafkaTemplate.executeInTransaction { it.send(EosChainListener.TOPIC, "chain-tx") }
		assertTrue(recorder.awaitDlt("chain-tx", 40), "체인이 DLT까지 도달하지 못했다")
		Thread.sleep(2_000)

		assertEquals(
			listOf("eos-chain", "eos-chain-retry-0", "eos-chain-retry-1", "eos-chain-dlt"),
			recorder.hopsFor("chain-tx"),
		)
		assertEquals(1, recorder.countDlt("chain-tx"), "홉마다 커밋되므로 중복이 없어야 한다")
	}

	@Test
	@DisplayName("체인 중간 홉의 커밋이 깨져도 다음 홉에 중복이 새지 않는다")
	fun `commit failure mid chain does not duplicate the next hop`() {
		producerGate.targetGroupId = EosChainListener.GROUP
		producerGate.startFailing()

		kafkaTemplate.executeInTransaction { it.send(EosChainListener.TOPIC, "chain-abort") }

		assertTrue(producerGate.awaitBlockedCommit(30), "체인 홉의 커밋 차단이 관측되지 않았다")
		producerGate.stopFailing()

		assertTrue(recorder.awaitDlt("chain-abort", 40), "복구 후 DLT까지 가지 못했다")
		Thread.sleep(3_000)

		assertEquals(1, recorder.countDlt("chain-abort"), "abort된 홉이 중복을 남겼다")
		// 커밋이 깨진 홉은 재처리되므로 그 토픽만 두 번 이상 보이고, 그 뒤 홉은 한 번씩만 보인다.
		val hops = recorder.hopsFor("chain-abort")
		assertEquals(1, hops.count { it == "eos-chain-retry-1" }, "abort 이후 홉이 중복됐다 → $hops")
		assertEquals(1, hops.count { it == "eos-chain-dlt" }, "DLT 홉이 중복됐다 → $hops")
	}
}
