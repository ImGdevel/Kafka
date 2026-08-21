package com.study.kafka.retry

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.retrytopic.DltStrategy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 브로커 없이 레지스트리 스캔과 검증 규칙만 확인한다.
 *
 * `DefaultListableBeanFactory`에 빈 정의만 등록하면 `getType`이 인스턴스화 없이 타입을 돌려주므로
 * 리스너를 실제로 띄우지 않고도 스캔 경로를 그대로 태울 수 있다.
 */
@DisplayName("CustomRetryDltPolicyRegistry 스캔과 검증")
class CustomRetryDltPolicyRegistryTest {

	class ValidBlockingListener {
		@CustomRetryAndDLT(attempts = "1", blockingAttempts = "3", blockingBackoffDelay = "50", dltTopicSuffix = "-dlt")
		@KafkaListener(topics = ["valid"])
		fun handle() = Unit
	}

	class NoDltListener {
		@CustomRetryAndDLT(attempts = "2", dltStrategy = DltStrategy.NO_DLT, dltTopicSuffix = "-dlt")
		@KafkaListener(topics = ["discarding"])
		fun handle() = Unit
	}

	class BothRetryModesListener {
		@CustomRetryAndDLT(attempts = "3", blockingAttempts = "3", dltTopicSuffix = "-dlt")
		@KafkaListener(topics = ["conflict"])
		fun handle() = Unit
	}

	private fun scan(vararg types: Class<*>): CustomRetryDltPolicyRegistry {
		val beanFactory = DefaultListableBeanFactory()
		types.forEach { beanFactory.registerBeanDefinition(it.simpleName, RootBeanDefinition(it)) }

		return CustomRetryDltPolicyRegistry(beanFactory).apply { afterSingletonsInstantiated() }
	}

	@Test
	@DisplayName("블로킹 설정을 정책으로 읽어 원본/DLT 토픽 양쪽에 색인한다")
	fun `blocking attributes are indexed`() {
		val registry = scan(ValidBlockingListener::class.java)

		with(assertNotNull(registry.findByTopic("valid"))) {
			assertEquals(3, blockingAttempts)
			assertEquals(50L, blockingBackoffDelay)
			assertEquals(1, attempts)
		}
		assertNotNull(registry.findByTopic("valid-dlt"))
	}

	@Test
	@DisplayName("재시도 토픽 이름은 원본 토픽 접두사로 되짚는다")
	fun `retry topic falls back to prefix match`() {
		val registry = scan(ValidBlockingListener::class.java)

		assertEquals("valid", assertNotNull(registry.findByTopic("valid-retry-0")).topics.single())
	}

	@Test
	@DisplayName("정책이 없는 토픽에도 재시도 0회 BackOff를 돌려준다")
	fun `unknown topic never yields null backoff`() {
		val registry = scan(ValidBlockingListener::class.java)

		assertEquals(0L, (registry.blockingBackOffFor("unknown", IllegalStateException()) as org.springframework.util.backoff.FixedBackOff).maxAttempts)
	}

	@Test
	@DisplayName("블로킹과 논블로킹을 동시에 켜면 기동 시점에 막는다")
	fun `both retry modes fail fast`() {
		val failure = assertFailsWith<IllegalStateException> { scan(BothRetryModesListener::class.java) }

		assertTrue(
			failure.message?.contains("총 시도 횟수가 9회") == true,
			"곱해진 시도 횟수를 알려주지 않는다: ${failure.message}",
		)
	}

	@Test
	@DisplayName("NO_DLT는 재시도 소진 후 메시지가 폐기되는 구성으로 표시된다")
	fun `no dlt policy is marked as discarding`() {
		val registry = scan(NoDltListener::class.java, ValidBlockingListener::class.java)

		assertTrue(assertNotNull(registry.findByTopic("discarding")).discardsExhaustedMessages)
		assertFalse(assertNotNull(registry.findByTopic("valid")).discardsExhaustedMessages)
	}
}
