package com.study.kafka.retry.noannotation.withtx

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.transaction.KafkaAwareTransactionManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 트랜잭션은 켰지만 애노테이션도, 전역 에러 핸들러 빈도 없을 때 실제로 무엇이 도는지 확인한다.
 *
 * `determineCommonErrorHandler()`는 `transactionManager != null`이면 즉석 `DefaultErrorHandler`를
 * 만들지 않고 `null`을 그대로 둔다. 그 대신 실패는 `AfterRollbackProcessor` 경로로 간다.
 * `AbstractMessageListenerContainer` 생성자가 필드 기본값으로 이미
 * `new DefaultAfterRollbackProcessor()`를 심어 두므로, 이건 "즉석 생성"이 아니라
 * "원래부터 있던 기본값"이라는 점이 논블로킹 경로와 다르다.
 *
 * 그 기본 인스턴스도 같은 `SeekUtils.DEFAULT_BACK_OFF = FixedBackOff(0, 9)`를 쓴다.
 * 트랜잭션 유무와 무관하게 프레임워크가 규정한 "기본 재시도 횟수"가 하나로 통일돼 있다는 뜻이다.
 */
@SpringBootTest(
	classes = [NoAnnotationTxApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"spring.kafka.consumer.isolation-level=read-committed",
		"spring.kafka.producer.transaction-id-prefix=plain-tx-",
	],
)
@EmbeddedKafka(partitions = 1, topics = [PlainTxListener.TOPIC])
@DisplayName("트랜잭션은 있지만 애노테이션도 전역 핸들러도 없을 때의 기본 동작")
class NoAnnotationTxDefaultBehaviorTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<Any, Any>

	@Autowired
	private lateinit var recorder: NoAnnotationRecorder

	@Autowired
	private lateinit var kafkaAdmin: KafkaAdmin

	@Autowired
	private lateinit var txManagers: ObjectProvider<KafkaAwareTransactionManager<*, *>>

	@Test
	@DisplayName("트랜잭션 컨테이너도 기본 AfterRollbackProcessor로 총 10회 재시도 후 조용히 커밋한다")
	fun `default AfterRollbackProcessor retries ten times then silently commits`() {
		assertEquals(1L, txManagers.stream().count(), "KafkaTransactionManager 빈이 없다 — 트랜잭션이 안 걸렸다")

		kafkaTemplate.executeInTransaction { it.send(PlainTxListener.TOPIC, "poison") }

		assertTrue(
			recorder.awaitCount("poison", 10, 40),
			"10회에 도달하지 못했다 → 누적=${recorder.countListener("poison")}",
		)

		Thread.sleep(3_000)
		assertEquals(10, recorder.countListener("poison"), "10회를 넘겨 계속 재시도했다")

		val topics = AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			admin.listTopics().names().get(10, TimeUnit.SECONDS)
		}
		assertEquals(setOf(PlainTxListener.TOPIC), topics.filter { it.startsWith("plain-tx") }.toSet())
	}
}
