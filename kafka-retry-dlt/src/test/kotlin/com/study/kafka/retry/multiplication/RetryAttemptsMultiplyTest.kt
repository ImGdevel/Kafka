package com.study.kafka.retry.multiplication

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 논블로킹 재시도(`attempts`)와 전역 블로킹 재시도가 동시에 걸리면 시도 횟수가 곱해진다는 것을
 * Spring Kafka 순정 API로 확인한다.
 *
 * `@CustomRetryAndDLT`는 정확히 이 조합을 기동 시점에 막는다
 * (`CustomRetryDltPolicyRegistry.validate`, 관련 테스트는 `CustomRetryDltPolicyRegistryTest`).
 * 그 가드가 왜 필요한지 근거를 남기려고, 가드가 없는 Spring 순정 `@RetryableTopic` +
 * `RetryTopicConfigurationSupport.configureBlockingRetries`로 같은 조합을 직접 구성해 재현한다.
 *
 * 즉 이 테스트가 통과한다고 해서 우리 모듈이 이 조합을 지원한다는 뜻이 아니다.
 * 반대로 "이 조합은 실제로 위험하다"는 것을 보여주는 대조군이다.
 */
@SpringBootTest(
	classes = [MultiplyApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
	],
)
@EmbeddedKafka(partitions = 1, topics = [MultiplyListener.TOPIC])
@DisplayName("논블로킹 재시도와 블로킹 재시도가 곱해지는 것 확인")
class RetryAttemptsMultiplyTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: MultiplyRecorder

	@Test
	@DisplayName("attempts=3 과 전역 블로킹 3회가 같이 걸리면 홉마다 3회씩, 총 9회 처리된다")
	fun `attempts and global blocking retries multiply per hop`() {
		kafkaTemplate.send(MultiplyListener.TOPIC, "poison")

		assertTrue(recorder.awaitDlt(60), "DLT까지 가지 못했다 → ${recorder.hops.toList()}")
		Thread.sleep(2_000)

		// 홉마다 블로킹이 다시 걸린다. 재시도 토픽도 결국 하나의 토픽이라
		// 거기서 실패해도 같은 backOffFunction이 다시 적용되기 때문이다.
		assertEquals(MultiplyListener.BLOCKING_PER_HOP, recorder.countFor(MultiplyListener.TOPIC))
		assertEquals(MultiplyListener.BLOCKING_PER_HOP, recorder.countFor(MultiplyListener.RETRY_100))
		assertEquals(MultiplyListener.BLOCKING_PER_HOP, recorder.countFor(MultiplyListener.RETRY_200))
		assertEquals(1, recorder.countFor(MultiplyListener.DLT))

		val businessLogicCalls = recorder.hops.count { it != MultiplyListener.DLT }
		assertEquals(
			MultiplyListener.NON_BLOCKING_HOPS * MultiplyListener.BLOCKING_PER_HOP,
			businessLogicCalls,
			"두 값의 곱과 실제 호출 수가 다르다 → ${recorder.hops.toList()}",
		)
	}
}
