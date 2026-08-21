package com.study.kafka.retry

import org.springframework.kafka.retrytopic.DltStrategy
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.FixedBackOff

/**
 * `@CustomRetryAndDLT`의 확장 속성을 리스너 단위로 정리한 값 객체.
 *
 * @param topics 리스너가 구독하는 원본 토픽들(플레이스홀더 해석 완료)
 * @param dltTopicSuffix 해석된 DLT 접미사
 * @param owner 담당 팀/채널
 * @param alertOnDlt DLT 적재 시 알림 발송 여부
 * @param attempts 논블로킹(재시도 토픽) 총 시도 횟수. 숫자로 해석할 수 없으면 null
 * @param blockingAttempts 로컬 스레드 총 처리 횟수. 1이면 블로킹 재시도 없음
 * @param blockingBackoffDelay 블로킹 재시도 간 고정 대기(ms)
 * @param blockingRetryOn 블로킹 대상 예외. 비어 있으면 전부
 * @param dltStrategy DLT 처리 전략. `NO_DLT`면 재시도 소진 후 메시지가 폐기된다
 * @param listenerId 로그 추적용 `클래스#메서드` 문자열
 */
data class CustomRetryDltPolicy(
	val topics: List<String>,
	val dltTopicSuffix: String,
	val owner: String,
	val alertOnDlt: Boolean,
	val attempts: Int?,
	val blockingAttempts: Int,
	val blockingBackoffDelay: Long,
	val blockingRetryOn: List<Class<out Throwable>>,
	val dltStrategy: DltStrategy,
	val listenerId: String,
) {

	/** 재시도가 소진되면 메시지가 어디에도 남지 않고 사라지는 구성인지. */
	val discardsExhaustedMessages: Boolean = dltStrategy == DltStrategy.NO_DLT
	val dltTopics: List<String> = topics.map { it + dltTopicSuffix }

	/** 블로킹 재시도를 하지 않을 때 쓰는 BackOff. `null`을 돌려주면 안 되므로 명시적으로 0회를 쓴다. */
	private val noBlockingRetry: BackOff = FixedBackOff(0L, 0L)

	private val blockingBackOff: BackOff =
		if (blockingAttempts > 1) FixedBackOff(blockingBackoffDelay, blockingAttempts - 1L) else noBlockingRetry

	/**
	 * 이 예외에 적용할 블로킹 BackOff를 고른다.
	 *
	 * `null`을 절대 돌려주지 않는다. `DefaultErrorHandler`의 backOffFunction이 null을 받으면
	 * 기본값 `FixedBackOff(0, 9)`로 떨어져 "블로킹 안 함"이 아니라 "간격 없이 10회 반복"이 된다.
	 */
	fun blockingBackOffFor(exception: Throwable?): BackOff =
		if (blockingAttempts > 1 && matchesBlockingException(exception)) blockingBackOff else noBlockingRetry

	private fun matchesBlockingException(exception: Throwable?): Boolean {
		if (blockingRetryOn.isEmpty()) return true

		var current = exception
		val seen = mutableSetOf<Throwable>()
		while (current != null && seen.add(current)) {
			if (blockingRetryOn.any { it.isInstance(current) }) return true
			current = current.cause
		}
		return false
	}
}
