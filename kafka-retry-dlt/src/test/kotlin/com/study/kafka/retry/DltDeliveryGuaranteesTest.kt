package com.study.kafka.retry

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 보장 수준 판정 규칙. 브로커 없이 조합만 확인한다.
 *
 * 가장 중요한 칸은 "트랜잭션은 켰는데 read_committed가 아닌" 경우다.
 * 설정이 반만 되어 EOS처럼 보이지만 실제로는 중복이 그대로 새어 나온다.
 */
@DisplayName("DLT 발행 보장 수준 판정")
class DltDeliveryGuaranteesTest {

	@Test
	@DisplayName("트랜잭션이 없으면 at-least-once다")
	fun `non transactional producer is at least once`() {
		assertEquals(
			DltDeliveryGuarantee.AT_LEAST_ONCE,
			DltDeliveryGuarantees.evaluate(producerTransactional = false, consumerIsolationLevel = null),
		)
		// 컨슈머만 read_committed로 둬도 발행 쪽이 원자적이지 않으면 소용없다.
		assertEquals(
			DltDeliveryGuarantee.AT_LEAST_ONCE,
			DltDeliveryGuarantees.evaluate(producerTransactional = false, consumerIsolationLevel = "read_committed"),
		)
	}

	@Test
	@DisplayName("트랜잭션 프로듀서 + read_committed 컨슈머면 exactly-once다")
	fun `transactional producer with read committed is exactly once`() {
		assertEquals(
			DltDeliveryGuarantee.EXACTLY_ONCE,
			DltDeliveryGuarantees.evaluate(producerTransactional = true, consumerIsolationLevel = "read_committed"),
		)
		// Boot 프로퍼티는 하이픈 표기를 쓰지만 컨슈머 설정으로는 언더스코어로 들어간다. 대소문자/공백도 관대하게 본다.
		assertEquals(
			DltDeliveryGuarantee.EXACTLY_ONCE,
			DltDeliveryGuarantees.evaluate(producerTransactional = true, consumerIsolationLevel = " READ_COMMITTED "),
		)
	}

	@Test
	@DisplayName("트랜잭션만 켜고 isolation.level을 안 바꾸면 EOS가 깨진 상태다")
	fun `transactional producer without read committed is broken`() {
		assertEquals(
			DltDeliveryGuarantee.BROKEN_EXACTLY_ONCE,
			DltDeliveryGuarantees.evaluate(producerTransactional = true, consumerIsolationLevel = null),
		)
		assertEquals(
			DltDeliveryGuarantee.BROKEN_EXACTLY_ONCE,
			DltDeliveryGuarantees.evaluate(producerTransactional = true, consumerIsolationLevel = "read_uncommitted"),
		)
	}
}
