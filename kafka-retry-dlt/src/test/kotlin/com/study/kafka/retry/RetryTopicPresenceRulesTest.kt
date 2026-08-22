package com.study.kafka.retry

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.kafka.retrytopic.DltStrategy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 어떤 토픽을 검사 대상으로 삼을지 결정하는 규칙. 브로커 없이 조합만 본다.
 *
 * 대상을 좁히는 조건이 세 개라 헷갈리기 쉽다. `autoCreateTopics=false`인 정책의 토픽만,
 * 그중 원본이 아닌 것만, 그리고 리스너가 실제로 구독 중인 것만 본다.
 */
@DisplayName("재시도/DLT 토픽 존재 검사 대상 판정")
class RetryTopicPresenceRulesTest {

	private fun policy(
		topic: String,
		autoCreateTopics: Boolean,
		listenerId: String = "Listener#handle",
		dltStrategy: DltStrategy = DltStrategy.FAIL_ON_ERROR,
	) = CustomRetryDltPolicy(
		topics = listOf(topic),
		dltTopicSuffix = "-dlt",
		owner = "",
		alertOnDlt = true,
		attempts = 3,
		blockingAttempts = 1,
		blockingBackoffDelay = 500L,
		blockingRetryOn = emptyList(),
		dltStrategy = dltStrategy,
		autoCreateTopics = autoCreateTopics,
		listenerId = listenerId,
	)

	private fun lookup(vararg policies: CustomRetryDltPolicy): (String) -> CustomRetryDltPolicy? = { topic ->
		policies.firstOrNull { p -> p.topics.any { topic.startsWith(it) } }
	}

	@Test
	@DisplayName("autoCreateTopics=false 정책의 재시도 토픽과 DLT가 대상이다")
	fun `retry and dlt topics of a manual policy are required`() {
		val manual = policy("orders", autoCreateTopics = false)

		val required = RetryTopicPresenceRules.requiredTopics(
			subscribedTopics = listOf("orders", "orders-retry-0", "orders-retry-1", "orders-dlt"),
			mainTopics = setOf("orders"),
			policyFor = lookup(manual),
		)

		assertEquals(setOf("orders-retry-0", "orders-retry-1", "orders-dlt"), required.keys)
	}

	@Test
	@DisplayName("원본 토픽은 대상이 아니다")
	fun `main topic is never required`() {
		val manual = policy("orders", autoCreateTopics = false)

		val required = RetryTopicPresenceRules.requiredTopics(
			subscribedTopics = listOf("orders"),
			mainTopics = setOf("orders"),
			policyFor = lookup(manual),
		)

		// 원본 토픽은 애노테이션이 만들어 내는 것이 아니라 사용자의 입력이다.
		assertEquals(emptyMap(), required)
	}

	@Test
	@DisplayName("autoCreateTopics=true 정책은 Spring이 만들어 주므로 대상이 아니다")
	fun `auto created policy is skipped`() {
		val auto = policy("orders", autoCreateTopics = true)

		val required = RetryTopicPresenceRules.requiredTopics(
			subscribedTopics = listOf("orders", "orders-retry-0", "orders-dlt"),
			mainTopics = setOf("orders"),
			policyFor = lookup(auto),
		)

		assertEquals(emptyMap(), required)
	}

	@Test
	@DisplayName("구독하지 않는 토픽은 대상이 아니다")
	fun `unsubscribed topics are not required() `() {
		val manual = policy("orders", autoCreateTopics = false)

		// 블로킹 전용이면 재시도 토픽 컨테이너가 없고, NO_DLT면 DLT 컨테이너가 없다.
		// 그래서 구독 목록만 보면 두 경우가 자연히 빠진다.
		val required = RetryTopicPresenceRules.requiredTopics(
			subscribedTopics = listOf("orders"),
			mainTopics = setOf("orders"),
			policyFor = lookup(manual),
		)

		assertEquals(emptyMap(), required)
	}

	@Test
	@DisplayName("없는 토픽만 골라내고 메시지에 토픽과 리스너를 담는다")
	fun `missing topics are reported with their listener`() {
		val manual = policy("orders", autoCreateTopics = false, listenerId = "OrderListener#handle")
		val required = RetryTopicPresenceRules.requiredTopics(
			subscribedTopics = listOf("orders-retry-0", "orders-dlt"),
			mainTopics = setOf("orders"),
			policyFor = lookup(manual),
		)

		val missing = RetryTopicPresenceRules.missingTopics(required, existingTopics = setOf("orders", "orders-dlt"))

		assertEquals(setOf("orders-retry-0"), missing.keys)

		val message = RetryTopicPresenceRules.describe(missing)
		assertTrue(message.contains("orders-retry-0"), message)
		assertTrue(message.contains("OrderListener#handle"), message)
		assertTrue(message.contains("autoCreateTopics"), message)
	}

	@Test
	@DisplayName("정책이 여럿이면 토픽별로 각자의 설정을 따른다")
	fun `each policy decides for its own topics`() {
		val manual = policy("orders", autoCreateTopics = false, listenerId = "OrderListener#handle")
		val auto = policy("audits", autoCreateTopics = true, listenerId = "AuditListener#handle")

		val required = RetryTopicPresenceRules.requiredTopics(
			subscribedTopics = listOf("orders-dlt", "audits-dlt"),
			mainTopics = setOf("orders", "audits"),
			policyFor = lookup(manual, auto),
		)

		assertEquals(setOf("orders-dlt"), required.keys)
	}
}
