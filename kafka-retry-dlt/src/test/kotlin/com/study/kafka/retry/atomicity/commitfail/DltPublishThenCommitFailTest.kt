package com.study.kafka.retry.atomicity.commitfail

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "비즈니스 로직 실패 → DLT 발행 성공 → 오프셋 커밋 실패" 구간을 재현한다.
 *
 * 검증하려는 명제: DLT 발행과 오프셋 커밋은 원자적이지 않다.
 * 발행이 끝난 뒤 커밋이 깨지면 그 레코드는 미처리로 남고, 컨슈머가 다시 뜨면
 * 재시도 체인을 처음부터 다시 타서 DLT에 같은 메시지가 한 번 더 쌓인다.
 *
 * 재현 방식
 *  1. ConsumerPostProcessor로 대상 그룹의 commit* 호출만 예외로 막는다.
 *  2. 포이즌 메시지를 흘려 DLT 1회 적재를 확인한다(발행은 커밋보다 먼저 일어난다).
 *  3. 커밋 차단을 풀고 컨테이너를 stop/start 해 크래시 후 재기동을 흉내낸다.
 *  4. 커밋된 오프셋이 없으므로 같은 레코드가 다시 소비되고 DLT가 2건이 된다.
 *
 * 오프셋을 못 지운다고 유실되는 것이 아니라 중복이 된다는 점이 핵심이다.
 * 즉 이 파이프라인의 보장은 at-least-once다.
 */
@SpringBootTest(
	classes = [CommitFailApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(partitions = 1, topics = [CommitFailListener.TOPIC])
@DisplayName("DLT 발행 후 오프셋 커밋 실패")
class DltPublishThenCommitFailTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: CommitFailRecorder

	@Autowired
	private lateinit var commitGate: CommitGate

	@Autowired
	private lateinit var endpointRegistry: KafkaListenerEndpointRegistry

	@Test
	@DisplayName("커밋이 깨지면 재기동 후 같은 메시지가 DLT에 한 번 더 쌓인다")
	fun `dlt publish is not atomic with the offset commit`() {
		recorder.expectMoreDlt(1)
		commitGate.startFailing()

		kafkaTemplate.send(CommitFailListener.TOPIC, "poison")

		// 1) 커밋이 막힌 상태에서도 DLT 발행은 이미 끝났다 → 발행이 커밋보다 앞선다.
		assertTrue(recorder.dltLatch.await(30, TimeUnit.SECONDS), "DLT 1차 적재가 일어나지 않았다")
		assertTrue(
			commitGate.commitAttempted.await(30, TimeUnit.SECONDS),
			"커밋 시도가 관측되지 않았다. 차단 게이트가 동작하지 않은 것이다",
		)
		assertEquals(listOf("poison"), recorder.dltDeliveries.toList())
		assertTrue(commitGate.failedCommits.get() > 0, "커밋이 실제로 실패하지 않았다")

		// 2) 크래시 후 재기동. 커밋된 오프셋이 없으므로 같은 레코드를 다시 읽는다.
		//    stop()은 종료 직전에 오프셋을 한 번 더 커밋하므로, 차단을 먼저 풀면
		//    그 커밋이 성공해 크래시가 아니라 정상 종료가 돼버린다. 그래서 stop 이후에 푼다.
		recorder.expectMoreDlt(1)
		val container = mainListenerContainer()
		container.stop()
		commitGate.stopFailing()
		container.start()

		assertTrue(recorder.dltLatch.await(30, TimeUnit.SECONDS), "재기동 후 DLT 2차 적재가 일어나지 않았다")

		// 3) 유실이 아니라 중복이다.
		assertEquals(listOf("poison", "poison"), recorder.dltDeliveries.toList())
		assertEquals(2, recorder.listenerDeliveries.size, "원본 리스너도 두 번 호출됐어야 한다")
	}

	private fun mainListenerContainer() = assertNotNull(
		endpointRegistry.getListenerContainer(CommitFailListener.LISTENER_ID),
		"컨테이너 id를 찾지 못했다. 등록된 id=${endpointRegistry.listenerContainerIds}",
	)
}
