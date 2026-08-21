package com.study.kafka.retry.naming

import org.apache.kafka.clients.admin.AdminClient
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * 재시도/DLT 토픽 이름 규칙을 고정한다.
 *
 * 토픽 이름은 모니터링 룰, 접두사 ACL, 운영 스크립트가 문자열로 물고 있는 계약이다.
 * 백오프 설정을 바꿨을 뿐인데 이름이 달라지면 그것들이 조용히 깨진다.
 * 특히 `-retry`와 `-retry-0`이 갈리는 조건이 직관과 다르므로 표로 박아둔다.
 */
@SpringBootTest(
	classes = [NamingTestApplication::class],
	properties = [
		"spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
		"app.kafka.retry.replication-factor=1",
	],
)
@EmbeddedKafka(
	partitions = 1,
	topics = [
		NamingListeners.SINGLE_RETRY,
		NamingListeners.INDEXED,
		NamingListeners.DELAY_SUFFIXED,
		NamingListeners.FIXED_SINGLE,
		NamingListeners.FIXED_MULTIPLE,
		NamingListeners.HYPHENATED,
	],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("재시도/DLT 토픽 이름 규칙")
class RetryTopicNamingTest {

	@Autowired
	private lateinit var kafkaAdmin: KafkaAdmin

	private lateinit var brokerTopics: List<String>

	@BeforeAll
	fun collectTopics() {
		Thread.sleep(3_000) // 재시도 토픽 자동 생성 대기
		brokerTopics = AdminClient.create(kafkaAdmin.configurationProperties).use { admin ->
			admin.listTopics().names().get(10, TimeUnit.SECONDS).filter { it.startsWith("name-") }.sorted()
		}
	}

	private fun topicsFor(prefix: String) = brokerTopics.filter { it.startsWith(prefix) }

	@Test
	@DisplayName("재시도 토픽이 하나면 인덱스가 붙지 않는다")
	fun `single retry topic has no index`() {
		assertEquals(
			listOf("name-a", "name-a-dlt", "name-a-retry"),
			topicsFor(NamingListeners.SINGLE_RETRY),
		)
	}

	@Test
	@DisplayName("재시도 토픽이 둘 이상이면 인덱스가 붙는다")
	fun `multiple retry topics are indexed`() {
		assertEquals(
			listOf("name-b", "name-b-dlt", "name-b-retry-0", "name-b-retry-1"),
			topicsFor(NamingListeners.INDEXED),
		)
	}

	@Test
	@DisplayName("SUFFIX_WITH_DELAY_VALUE는 인덱스 대신 지연 값을 붙인다")
	fun `delay value suffixing uses the backoff interval`() {
		assertEquals(
			listOf("name-c", "name-c-dlt", "name-c-retry-100", "name-c-retry-200"),
			topicsFor(NamingListeners.DELAY_SUFFIXED),
		)
	}

	@Test
	@DisplayName("이름이 갈리는 진짜 기준은 재시도 횟수가 아니라 간격 재사용 전략이다")
	fun `same interval reuse strategy decides whether an index appears`() {
		// 둘 다 attempts=4다. 간격이 모두 같을 때 SINGLE_TOPIC은 한 토픽으로 합치고,
		// 그 결과 인덱스가 사라진다. 횟수를 보고 이름을 예측하면 틀린다.
		assertEquals(
			listOf("name-d", "name-d-dlt", "name-d-retry"),
			topicsFor(NamingListeners.FIXED_SINGLE),
		)
		assertEquals(
			listOf("name-e", "name-e-dlt", "name-e-retry-0", "name-e-retry-1", "name-e-retry-2"),
			topicsFor(NamingListeners.FIXED_MULTIPLE),
		)
	}

	@Test
	@DisplayName("원본 토픽에 하이픈이 있으면 접미사 경계가 이름만으로 구분되지 않는다")
	fun `hyphenated topic names blur the suffix boundary`() {
		assertEquals(
			listOf(
				"name-f-order-events",
				"name-f-order-events-dlt",
				"name-f-order-events-retry-0",
				"name-f-order-events-retry-1",
			),
			topicsFor(NamingListeners.HYPHENATED),
		)
	}
}
