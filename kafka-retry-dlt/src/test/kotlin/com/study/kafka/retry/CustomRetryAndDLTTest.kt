package com.study.kafka.retry

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.core.annotation.RepeatableContainers
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.DltStrategy
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `@CustomRetryAndDLT`가 Spring Kafka 입장에서 `@RetryableTopic`으로 보이는지 검증한다.
 *
 * 조회 방식은 `RetryTopicConfigurationProvider.getRetryableTopicAnnotationFromAnnotatedElement`와 동일하게
 * `MergedAnnotations` + `synthesize`를 사용한다. 프레임워크가 실제로 쓰는 경로를 그대로 재현해야
 * "테스트는 통과하는데 런타임에서는 안 잡히는" 상황을 막을 수 있다.
 */
class CustomRetryAndDLTTest {

	class DefaultsListener {
		@CustomRetryAndDLT
		fun listen() = Unit
	}

	class OverriddenListener {
		@CustomRetryAndDLT(
			attempts = "5",
			dltTopicSuffix = "-dead-letter",
			exclude = [IllegalArgumentException::class],
			dltStrategy = DltStrategy.NO_DLT,
			owner = "payments-team",
			alertOnDlt = false,
		)
		fun listen() = Unit
	}

	private fun listenerMethod(type: Class<*>): Method = type.getDeclaredMethod("listen")

	private fun retryableTopicOf(type: Class<*>): RetryableTopic? =
		MergedAnnotations.from(
			listenerMethod(type),
			MergedAnnotations.SearchStrategy.TYPE_HIERARCHY,
			RepeatableContainers.none(),
		)
			.get(RetryableTopic::class.java)
			.synthesize { it.isPresent }
			.orElse(null)

	@Test
	@DisplayName("메타 애노테이션만 붙여도 Spring Kafka는 @RetryableTopic으로 인식한다")
	fun `is discovered as RetryableTopic`() {
		assertNotNull(retryableTopicOf(DefaultsListener::class.java))
	}

	@Test
	@DisplayName("커스텀 기본값이 @RetryableTopic 속성으로 그대로 위임된다")
	fun `custom defaults are propagated`() {
		val annotation = assertNotNull(retryableTopicOf(DefaultsListener::class.java))

		assertEquals("\${app.kafka.retry.attempts:3}", annotation.attempts)
		assertEquals("\${app.kafka.retry.retry-topic-suffix:.retry}", annotation.retryTopicSuffix)
		assertEquals("\${app.kafka.retry.dlt-topic-suffix:.dlt}", annotation.dltTopicSuffix)
		assertEquals("\${app.kafka.retry.backoff.delay:1000}", annotation.backoff.delayExpression)
		assertEquals("\${app.kafka.retry.backoff.multiplier:2.0}", annotation.backoff.multiplierExpression)
		assertEquals("\${app.kafka.retry.backoff.max-delay:10000}", annotation.backoff.maxDelayExpression)
		// Spring Kafka 기본값(SUFFIX_WITH_DELAY_VALUE / ALWAYS_RETRY_ON_ERROR)과 다르게 잡아둔 값
		assertEquals(TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, annotation.topicSuffixingStrategy)
		assertEquals(DltStrategy.FAIL_ON_ERROR, annotation.dltStrategy)
	}

	@Test
	@DisplayName("사용처에서 지정한 값이 @AliasFor로 오버라이드된다")
	fun `explicit values override aliased attributes`() {
		val annotation = assertNotNull(retryableTopicOf(OverriddenListener::class.java))

		assertEquals("5", annotation.attempts)
		assertEquals("-dead-letter", annotation.dltTopicSuffix)
		assertEquals(DltStrategy.NO_DLT, annotation.dltStrategy)
		assertEquals(1, annotation.exclude.size)
		assertEquals<KClass<*>>(IllegalArgumentException::class, annotation.exclude[0])
		// 지정하지 않은 속성은 우리 기본값을 유지한다
		assertEquals("\${app.kafka.retry.retry-topic-suffix:.retry}", annotation.retryTopicSuffix)
	}

	@Test
	@DisplayName("확장 속성은 @RetryableTopic이 아니라 CustomRetryDltAttributes로 읽는다")
	fun `extension attributes are readable`() {
		val defaults = assertNotNull(CustomRetryDltAttributes.find(listenerMethod(DefaultsListener::class.java)))
		assertEquals("", defaults.owner)
		assertTrue(defaults.alertOnDlt)

		val overridden = assertNotNull(CustomRetryDltAttributes.find(listenerMethod(OverriddenListener::class.java)))
		assertEquals("payments-team", overridden.owner)
		assertFalse(overridden.alertOnDlt)
	}
}
