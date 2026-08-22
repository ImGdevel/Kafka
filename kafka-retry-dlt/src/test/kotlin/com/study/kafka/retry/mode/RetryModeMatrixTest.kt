package com.study.kafka.retry.mode

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 블로킹/논블로킹 × DLT 유무 4분면이 각각 의도한 경로로만 흐르는지 확인한다.
 *
 * "선택한 노선을 탄다"만으로는 부족하다. 선택하지 않은 노선을 **안 타는지**도 봐야 하므로
 * 방문 순서와 브로커에 실제로 만들어진 토픽 목록을 둘 다 검증한다.
 * 재시도 토픽은 존재 자체가 논블로킹 노선을 탔다는 증거다.
 */
@SpringBootTest(
	classes = [RetryModeTestApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"app.kafka.retry.auto-create-topics=true",
		"app.kafka.retry.replication-factor=1",
		"app.kafka.retry.backoff.delay=100",
		"app.kafka.retry.backoff.multiplier=2.0",
	],
)
@EmbeddedKafka(
	partitions = 1,
	topics = [
		BlockingWithDltListener.TOPIC,
		BlockingWithoutDltListener.TOPIC,
		NonBlockingWithDltListener.TOPIC,
		NonBlockingWithoutDltListener.TOPIC,
		BlockingFilteredListener.TOPIC,
	],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("재시도 모드 4분면")
class RetryModeMatrixTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: ModeRecorder

	@Autowired
	private lateinit var kafkaAdmin: KafkaAdmin

	private lateinit var brokerTopics: List<String>

	/**
	 * 다섯 리스너를 한 번에 태우고 모두 끝날 때까지 기다린다.
	 * 컨텍스트 기동 비용(EmbeddedKafka)이 크고 각 리스너가 독립이라 한 번만 흘려보낸다.
	 */
	@BeforeAll
	fun runAllModes() {
		recorder.expect(BlockingWithDltListener.KEY, 4)      // 로컬 3회 + DLT 1회
		recorder.expect(BlockingWithoutDltListener.KEY, 3)   // 로컬 3회, 그 뒤 폐기
		recorder.expect(NonBlockingWithDltListener.KEY, 4)   // 원본 + 재시도 2 + DLT
		recorder.expect(NonBlockingWithoutDltListener.KEY, 3) // 원본 + 재시도 2, 그 뒤 폐기
		recorder.expect(BlockingFilteredListener.KEY, 2)     // 로컬 1회 + DLT 1회

		listOf(
			BlockingWithDltListener.TOPIC,
			BlockingWithoutDltListener.TOPIC,
			NonBlockingWithDltListener.TOPIC,
			NonBlockingWithoutDltListener.TOPIC,
			BlockingFilteredListener.TOPIC,
		).forEach { kafkaTemplate.send(it, "poison") }

		listOf(
			BlockingWithDltListener.KEY,
			BlockingWithoutDltListener.KEY,
			NonBlockingWithDltListener.KEY,
			NonBlockingWithoutDltListener.KEY,
			BlockingFilteredListener.KEY,
		).forEach { assertTrue(recorder.await(it), "$it: 기대한 방문 횟수에 도달하지 못했다 → ${recorder.of(it)}") }

		// 래치가 열린 뒤에도 남은 경로가 더 흐르지 않는지 확인할 여유를 준다.
		Thread.sleep(2_000)

		brokerTopics = AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			admin.listTopics().names().get(10, TimeUnit.SECONDS).filter { it.startsWith("mode-") }.sorted()
		}
	}

	@Test
	@DisplayName("1분면 blocking + DLT: 같은 토픽에서 3회 처리 후 DLT")
	fun `blocking with dlt`() {
		assertEquals(
			listOf("mode-bd", "mode-bd", "mode-bd", "mode-bd.dlt"),
			recorder.of(BlockingWithDltListener.KEY),
		)
		assertTrue("mode-bd.dlt" in brokerTopics)
		assertNoRetryTopicFor("mode-bd")
	}

	@Test
	@DisplayName("2분면 blocking + DLT 없음: 3회 처리 후 폐기, 토픽은 원본 하나뿐")
	fun `blocking without dlt`() {
		assertEquals(
			listOf("mode-bn", "mode-bn", "mode-bn"),
			recorder.of(BlockingWithoutDltListener.KEY),
		)
		assertFalse("mode-bn.dlt" in brokerTopics, "NO_DLT인데 DLT 토픽이 생겼다")
		assertNoRetryTopicFor("mode-bn")
	}

	@Test
	@DisplayName("3분면 non-blocking + DLT: 각 토픽 1회씩 거쳐 DLT")
	fun `non blocking with dlt`() {
		assertEquals(
			listOf("mode-nd", "mode-nd.retry-0", "mode-nd.retry-1", "mode-nd.dlt"),
			recorder.of(NonBlockingWithDltListener.KEY),
		)
		assertTrue("mode-nd.dlt" in brokerTopics)
	}

	@Test
	@DisplayName("4분면 non-blocking + DLT 없음: 재시도 토픽까지만 가고 폐기")
	fun `non blocking without dlt`() {
		assertEquals(
			listOf("mode-nn", "mode-nn.retry-0", "mode-nn.retry-1"),
			recorder.of(NonBlockingWithoutDltListener.KEY),
		)
		assertFalse("mode-nn.dlt" in brokerTopics, "NO_DLT인데 DLT 토픽이 생겼다")
	}

	@Test
	@DisplayName("blockingRetryOn에 없는 예외는 블로킹하지 않는다")
	fun `blocking is filtered by exception type`() {
		// blockingAttempts=3이지만 blockingRetryOn=[IllegalArgumentException]이고 실제 예외는 IllegalStateException이다.
		assertEquals(
			listOf("mode-bx", "mode-bx.dlt"),
			recorder.of(BlockingFilteredListener.KEY),
		)
	}

	private fun assertNoRetryTopicFor(topic: String) {
		val retryTopics = brokerTopics.filter { it.startsWith("$topic.retry") }
		assertEquals(emptyList(), retryTopics, "블로킹 모드인데 재시도 토픽이 만들어졌다")
	}
}
