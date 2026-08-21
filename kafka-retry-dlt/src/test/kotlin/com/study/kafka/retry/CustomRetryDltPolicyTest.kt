package com.study.kafka.retry

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.kafka.retrytopic.DltStrategy
import org.springframework.util.backoff.FixedBackOff
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 블로킹 BackOff 선택 규칙을 브로커 없이 검증한다.
 *
 * 가장 중요한 명제는 "절대 null을 돌려주지 않는다"다.
 * `DefaultErrorHandler.setBackOffFunction`이 null을 받으면 블로킹을 끄는 게 아니라
 * 기본값 `FixedBackOff(0, 9)`로 떨어져 간격 없이 10회 반복한다.
 */
@DisplayName("CustomRetryDltPolicy 블로킹 BackOff 선택")
class CustomRetryDltPolicyTest {

	private fun policy(
		blockingAttempts: Int,
		blockingBackoffDelay: Long = 500L,
		blockingRetryOn: List<Class<out Throwable>> = emptyList(),
	) = CustomRetryDltPolicy(
		topics = listOf("orders"),
		dltTopicSuffix = "-dlt",
		owner = "team",
		alertOnDlt = true,
		attempts = 1,
		blockingAttempts = blockingAttempts,
		blockingBackoffDelay = blockingBackoffDelay,
		blockingRetryOn = blockingRetryOn,
		dltStrategy = DltStrategy.FAIL_ON_ERROR,
		listenerId = "Listener#handle",
	)

	@Test
	@DisplayName("blockingAttempts=1이면 재시도 0회 BackOff를 돌려준다")
	fun `no blocking retry when attempts is one`() {
		val backOff = assertIs<FixedBackOff>(policy(blockingAttempts = 1).blockingBackOffFor(IllegalStateException()))

		assertEquals(0L, backOff.maxAttempts)
	}

	@Test
	@DisplayName("blockingAttempts=2면 최초 1회 + 재시도 1회 = 총 2회")
	fun `blocking attempts counts the first try`() {
		val backOff = assertIs<FixedBackOff>(
			policy(blockingAttempts = 2, blockingBackoffDelay = 50L).blockingBackOffFor(IllegalStateException()),
		)

		// attempts와 같은 셈법이다. 총 횟수이지 재시도 횟수가 아니다.
		assertEquals(1L, backOff.maxAttempts)
		assertEquals(50L, backOff.interval)
	}

	@Test
	@DisplayName("blockingAttempts=3이면 재시도 2회짜리 BackOff를 돌려준다")
	fun `blocking retry count is attempts minus one`() {
		val backOff = assertIs<FixedBackOff>(
			policy(blockingAttempts = 3, blockingBackoffDelay = 50L).blockingBackOffFor(IllegalStateException()),
		)

		assertEquals(2L, backOff.maxAttempts)
		assertEquals(50L, backOff.interval)
	}

	@Test
	@DisplayName("blockingRetryOn에 없는 예외는 블로킹하지 않는다")
	fun `exception outside blockingRetryOn is not retried`() {
		val target = policy(blockingAttempts = 3, blockingRetryOn = listOf(IllegalArgumentException::class.java))
		val backOff = assertIs<FixedBackOff>(target.blockingBackOffFor(IllegalStateException()))

		assertEquals(0L, backOff.maxAttempts)
	}

	@Test
	@DisplayName("리스너 예외가 감싸여 있어도 cause 체인을 훑어 판정한다")
	fun `cause chain is traversed`() {
		val target = policy(blockingAttempts = 3, blockingRetryOn = listOf(IllegalArgumentException::class.java))
		// 실제로는 ListenerExecutionFailedException이 리스너 예외를 감싸서 들어온다.
		val wrapped = RuntimeException("listener failed", IllegalArgumentException("real cause"))

		val backOff = assertIs<FixedBackOff>(target.blockingBackOffFor(wrapped))

		assertEquals(2L, backOff.maxAttempts)
	}

	@Test
	@DisplayName("예외가 null이어도 null을 돌려주지 않는다")
	fun `never returns null`() {
		val backOff = assertIs<FixedBackOff>(policy(blockingAttempts = 3).blockingBackOffFor(null))

		assertEquals(2L, backOff.maxAttempts)
	}
}
