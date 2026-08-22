package com.study.kafka.retry.pollinterval

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 블로킹 재시도가 `max.poll.interval.ms` 를 어떻게 소모하는지 확인한다.
 *
 * 여기서 알고 싶은 것은 "무엇과 상한을 비교해야 하는가"다.
 * 전체 블로킹 구간(`delay * (attempts - 1)`)인지, 개별 백오프 하나인지에 따라
 * 안전한 설정 범위가 완전히 달라진다.
 *
 * `max.poll.interval.ms` 는 5초로 낮춰 둔다.
 */
@SpringBootTest(
	classes = [PollIntervalApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"spring.kafka.consumer.properties.max.poll.interval.ms=5000",
		"spring.kafka.consumer.properties.session.timeout.ms=10000",
		"spring.kafka.consumer.properties.heartbeat.interval.ms=1000",
		"app.kafka.retry.auto-create-topics=true",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(
	partitions = 1,
	topics = [TotalWindowExceedsListener.TOPIC, SingleBackoffExceedsListener.TOPIC],
)
@DisplayName("블로킹 재시도와 max.poll.interval.ms")
class BlockingRetryPollIntervalTest {

	@Autowired
	private lateinit var kafkaTemplate: KafkaTemplate<String, String>

	@Autowired
	private lateinit var recorder: PollIntervalRecorder

	@Test
	@DisplayName("전체 블로킹 구간이 상한을 넘어도 개별 백오프가 짧으면 정상 처리된다")
	fun `total blocking window may exceed max poll interval`() {
		// 3회 × 3초 = 약 6초로 max.poll.interval.ms(5초)를 넘긴다.
		kafkaTemplate.send(TotalWindowExceedsListener.TOPIC, "total")

		assertTrue(recorder.awaitDlt("total", 60), "DLT까지 가지 못했다")
		Thread.sleep(3_000)

		println("POLL-TOTAL listenerCalls=${recorder.countListener("total")} dlt=${recorder.countDlt("total")}")
		assertEquals(3, recorder.countListener("total"), "재시도 횟수가 blockingAttempts와 다르다")
		assertEquals(1, recorder.countDlt("total"), "리밸런스로 중복 처리가 일어났다")
	}

	@Test
	@DisplayName("개별 백오프 하나가 상한을 넘으면 어떻게 되는지 관찰한다")
	fun `single backoff longer than max poll interval`() {
		// 9초 백오프 한 번으로 max.poll.interval.ms(5초)를 단독으로 초과한다.
		kafkaTemplate.send(SingleBackoffExceedsListener.TOPIC, "single")

		assertTrue(recorder.awaitDlt("single", 90), "DLT까지 가지 못했다")
		Thread.sleep(3_000)

		println("POLL-SINGLE listenerCalls=${recorder.countListener("single")} dlt=${recorder.countDlt("single")}")
	}
}
