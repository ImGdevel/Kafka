package com.study.kafka.retry

/**
 * `@CustomRetryAndDLT`의 확장 속성을 리스너 단위로 정리한 값 객체.
 *
 * @param topics 리스너가 구독하는 원본 토픽들(플레이스홀더 해석 완료)
 * @param dltTopicSuffix 해석된 DLT 접미사
 * @param owner 담당 팀/채널
 * @param alertOnDlt DLT 적재 시 알림 발송 여부
 * @param listenerId 로그 추적용 `클래스#메서드` 문자열
 */
data class CustomRetryDltPolicy(
	val topics: List<String>,
	val dltTopicSuffix: String,
	val owner: String,
	val alertOnDlt: Boolean,
	val listenerId: String,
) {
	val dltTopics: List<String> = topics.map { it + dltTopicSuffix }
}
