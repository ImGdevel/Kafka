package com.study.kafka.retry.blocking

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 블로킹 재시도를 쓸 때 DLT 발행이 목표대로 동작하는지 확인한다.
 *
 * 확인할 것은 세 가지다.
 *  1. 블로킹 재시도가 설정한 횟수를 끝까지 채우고 DLT로 넘어간다.
 *  2. 발행 뒤 오프셋 커밋이 깨져도 DLT에 중복이 남지 않는다.
 *  3. DLT 발행 자체가 깨지면 메시지를 잃지 않고, 발행이 살아나면 그대로 DLT로 간다.
 *
 * 2번이 원자성의 핵심이다. 블로킹 재시도 중 발행이 일어나는 시도는 마지막 하나뿐이므로,
 * 원자적으로 묶여야 하는 구간도 그 한 사이클이다. 소진 전 시도들은 되감기만 하고 끝나
 * 발행이 없으니 롤백돼도 잃을 것이 없다.
 *
 * 트랜잭션을 켠 구성이다. 컨슈머가 `read_committed`여야 abort 된 발행분이 걸러진다.
 */
@SpringBootTest(
	classes = [BlockingAtomicityApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"spring.kafka.consumer.isolation-level=read-committed",
		"spring.kafka.producer.transaction-id-prefix=blk-tx-",
		"app.kafka.retry.auto-create-topics=true",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(
	partitions = 1,
	topics = [BlockingOnlyListener.TOPIC, CommitFailListener.TOPIC, SendFailListener.TOPIC],
)
@DisplayName("블로킹 재시도와 DLT 발행 원자성")
class BlockingRetryDltAtomicityTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

	@Autowired
	private lateinit var recorder: BlockingRecorder

	@Autowired
	private lateinit var commitFailureGate: CommitFailureGate

	@Autowired
	private lateinit var sendFailureGate: SendFailureGate

	@Test
	@DisplayName("블로킹 재시도가 설정한 횟수를 채우고 DLT로 넘어간다")
	fun `blocking retries run to completion and hand off to the dlt`() {
		kafkaTemplate.executeInTransaction { it.send(BlockingOnlyListener.TOPIC, "plain") }

		assertTrue(recorder.awaitDlt("plain", 60), "DLT까지 가지 못했다")
		Thread.sleep(2_000)

		assertEquals(
			BlockingOnlyListener.BLOCKING_ATTEMPTS,
			recorder.countListener("plain"),
			"블로킹 재시도가 중간에 끊겼다",
		)
		assertEquals(1, recorder.countDlt("plain"), "DLT 적재가 중복되거나 누락됐다")
	}

	@Test
	@DisplayName("발행 뒤 커밋이 깨져도 DLT에 중복이 남지 않는다")
	fun `commit failure after the dlt publish leaves no duplicate`() {
		commitFailureGate.startFailing(CommitFailListener.GROUP)
		val sendsBefore = commitFailureGate.dltSendAttempts.get()

		kafkaTemplate.executeInTransaction { it.send(CommitFailListener.TOPIC, "commit-fail") }

		// 1) 마지막 시도에서 DLT 발행까지 끝난 뒤 커밋이 막힌다.
		assertTrue(commitFailureGate.awaitBlockedCommit(60), "커밋 차단이 관측되지 않았다")
		assertTrue(commitFailureGate.failedCommits.get() > 0, "커밋이 실제로 실패하지 않았다")

		// 2) 차단을 풀면 롤백된 레코드가 재처리돼 블로킹을 다시 돌고 이번엔 정상 커밋된다.
		commitFailureGate.stopFailing()
		assertTrue(recorder.awaitDlt("commit-fail", 60), "복구 후에도 DLT 적재가 없었다")
		Thread.sleep(3_000)

		// 3) 발행은 두 번 이상 나갔지만 abort 된 쪽은 read_committed 컨슈머에게 보이지 않는다.
		assertTrue(
			commitFailureGate.dltSendAttempts.get() - sendsBefore >= 2,
			"발행이 한 번뿐이면 abort 후 재발행 시나리오가 재현되지 않은 것이다",
		)
		assertEquals(1, recorder.countDlt("commit-fail"), "abort 된 DLT 레코드가 소비됐다")

		// 4) 재처리로 블로킹이 한 벌 더 돌았다. 리스너는 멱등해야 한다.
		assertTrue(
			recorder.countListener("commit-fail") >= CommitFailListener.BLOCKING_ATTEMPTS * 2,
			"롤백 후 재처리가 일어나지 않았다 → ${recorder.countListener("commit-fail")}",
		)
	}

	@Test
	@DisplayName("DLT 발행이 실패하면 버려지지 않고, 발행이 살아나면 그대로 DLT로 간다")
	fun `failed dlt publish never loses the record`() {
		kafkaTemplate.executeInTransaction { it.send(SendFailListener.TOPIC, "send-fail") }

		// 1) 발행이 막힌 동안 블로킹 한 벌이 통째로 반복된다.
		val twoRounds = SendFailListener.BLOCKING_ATTEMPTS * 2
		assertTrue(
			recorder.awaitListener("send-fail", twoRounds, 60),
			"재처리가 반복되지 않았다 → ${recorder.countListener("send-fail")}",
		)
		assertTrue(sendFailureGate.attempts.get() > 0, "DLT 발행 시도 자체가 없었다")
		assertEquals(0, recorder.countDlt("send-fail"), "발행이 실패했는데 DLT가 소비됐다")

		// 2) 발행이 복구되면 정체돼 있던 레코드가 DLT로 넘어간다. 유실이 아니라 지연이었다.
		sendFailureGate.stopFailing()
		assertTrue(recorder.awaitDlt("send-fail", 60), "발행 복구 후에도 DLT 적재가 없었다")
		Thread.sleep(3_000)

		assertEquals(1, recorder.countDlt("send-fail"), "DLT 적재가 중복됐다")
	}
}
