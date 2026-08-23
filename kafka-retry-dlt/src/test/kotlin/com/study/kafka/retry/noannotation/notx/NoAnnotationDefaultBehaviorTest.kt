package com.study.kafka.retry.noannotation.notx

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@CustomRetryAndDLT`도, 전역 `CommonErrorHandler` 빈도 없을 때 실제로 무엇이 도는지 확인한다.
 *
 * 컨테이너는 빈을 찾지 못하면(그리고 트랜잭션 매니저도 없으면) 그 자리에서
 * `new DefaultErrorHandler()`를 즉석 생성한다(`ListenerConsumer.determineCommonErrorHandler`).
 * 이건 스프링 빈이 아니라 프레임워크 내부 객체라서 컨텍스트 어디서도 조회할 수 없다.
 *
 * 그 기본 인스턴스의 백오프는 `SeekUtils.DEFAULT_BACK_OFF = FixedBackOff(0, 9)`,
 * 즉 최초 1회 + 재시도 9회 = 총 10회이고 recoverer가 없어(null) 소진 후에는
 * 로깅 전용 recoverer로 대체되어 그냥 커밋(스킵)한다. 아무 곳에도 발행되지 않는다.
 */
@SpringBootTest(
	classes = [NoAnnotationApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
	],
)
@EmbeddedKafka(partitions = 1, topics = [PlainListener.TOPIC])
@DisplayName("애노테이션도 전역 에러 핸들러도 없을 때의 기본 동작")
class NoAnnotationDefaultBehaviorTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: NoAnnotationRecorder

	@Autowired
	private lateinit var kafkaAdmin: KafkaAdmin

	@Test
	@DisplayName("총 10회(1+9) 재시도 후 조용히 커밋되고 어디에도 발행되지 않는다")
	fun `default DefaultErrorHandler retries ten times then silently commits`() {
		kafkaTemplate.send(PlainListener.TOPIC, "poison")

		assertTrue(
			recorder.awaitCount("poison", 10, 30),
			"10회에 도달하지 못했다 → 누적=${recorder.countListener("poison")}",
		)

		// 소진 후 더 재시도하지 않는지, 그리고 정확히 10에서 멈추는지 확인할 여유를 준다.
		Thread.sleep(3_000)
		assertEquals(10, recorder.countListener("poison"), "10회를 넘겨 계속 재시도했다")

		// 어떤 DLT/재시도 토픽도 만들어지지 않았다. 우리 인프라가 전혀 관여하지 않았다는 증거다.
		val topics = AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			admin.listTopics().names().get(10, TimeUnit.SECONDS)
		}
		assertEquals(setOf(PlainListener.TOPIC), topics.filter { it.startsWith("plain") }.toSet())
	}
}
