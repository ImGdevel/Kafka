package com.study.kafka.retry.atomicity.sendfail

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "비즈니스 로직 실패 → DLT 발행 실패" 구간을 재현한다.
 *
 * 검증하려는 명제: 발행이 실패하면 메시지는 유실되지 않는다.
 * `DeadLetterPublishingRecovererFactory`가 `setFailIfSendResultIsError(true)`로 만들고
 * `verifySendResult`가 전송 결과를 블로킹으로 확인하므로, 발행 실패는 예외가 되어
 * 오프셋이 커밋되지 않는다. 따라서 같은 레코드가 계속 재전달된다.
 *
 * 뒤집어 말하면 DLT 브로커/토픽이 죽어 있는 동안 컨슈머는 그 파티션에서 진행하지 못하고
 * 같은 레코드를 무한히 재처리한다. 조용히 버리지 않는 대신 정체된다는 뜻이다.
 */
@SpringBootTest(
	classes = [SendFailApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(partitions = 1, topics = [SendFailListener.TOPIC])
@DisplayName("DLT 발행 실패")
class DltPublishFailTest {

	// 이 컨텍스트는 KafkaTemplate 빈을 직접 정의하므로 제네릭이 <Any, Any>로 고정돼 있다.
	// Boot 자동설정 템플릿(<?, ?>)과 달리 <String, String>으로는 주입되지 않는다.
	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

	@Autowired
	private lateinit var recorder: SendFailRecorder

	@Autowired
	private lateinit var sendGate: SendGate

	@Test
	@DisplayName("발행이 실패하면 버려지지 않고 계속 재전달되며, 발행이 살아나면 DLT로 들어간다")
	fun `failed dlt publish does not lose the record`() {
		kafkaTemplate.send(SendFailListener.TOPIC, "poison")

		// 1) 발행이 막힌 동안 같은 레코드가 반복 재전달된다.
		assertTrue(
			recorder.redeliveryLatch.await(30, TimeUnit.SECONDS),
			"재전달이 3회에 도달하지 않았다 → 누적=${recorder.listenerDeliveries.size}",
		)
		assertTrue(sendGate.attemptedDltSends.get() > 0, "DLT 발행 시도 자체가 없었다")

		// 2) 발행이 실패했으므로 DLT 핸들러는 아직 아무것도 못 받았다.
		assertEquals(emptyList(), recorder.dltDeliveries.toList(), "발행이 실패했는데 DLT가 소비됐다")

		// 3) 발행이 복구되면 정체돼 있던 레코드가 DLT로 넘어간다. 유실이 아니라 지연이었다.
		sendGate.stopFailing()
		assertTrue(recorder.dltLatch.await(30, TimeUnit.SECONDS), "발행 복구 후에도 DLT 적재가 없었다")
		assertEquals(listOf("poison"), recorder.dltDeliveries.toList())

		// 4) 재전달된 횟수만큼 비즈니스 로직이 반복 실행됐다. 핸들러는 멱등해야 한다.
		assertTrue(
			recorder.listenerDeliveries.size >= 3,
			"재전달 횟수=${recorder.listenerDeliveries.size}",
		)
	}
}
