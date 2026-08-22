package com.study.kafka.retry

import org.springframework.core.annotation.MergedAnnotations
import org.springframework.core.annotation.RepeatableContainers
import java.lang.reflect.AnnotatedElement

/**
 * [CustomRetryAndDLT]의 확장 속성(owner, alertOnDlt 등)을 읽는 헬퍼.
 *
 * Spring Kafka는 합성된 `@RetryableTopic`만 보기 때문에 우리 확장 속성은 직접 읽어야 한다.
 * 조회 전략은 `RetryTopicConfigurationProvider`와 동일하게 맞춰,
 * Spring Kafka가 재시도 설정을 잡아낸 지점과 우리가 메타데이터를 읽는 지점이 어긋나지 않게 한다.
 */
object CustomRetryDltAttributes {

	fun find(element: AnnotatedElement): CustomRetryAndDLT? =
		MergedAnnotations.from(element, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY, RepeatableContainers.none())
			.get(CustomRetryAndDLT::class.java)
			.synthesize { it.isPresent }
			.orElse(null)
}
