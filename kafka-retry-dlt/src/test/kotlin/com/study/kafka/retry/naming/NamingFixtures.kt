package com.study.kafka.retry.naming

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.CustomRetryDltConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.retry.annotation.Backoff
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component

@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class NamingTestApplication {
	@Bean fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })
}

/**
 * 재시도/DLT 토픽 이름 규칙을 고정하기 위한 리스너들.
 *
 * 메시지를 흘리지 않는다. 재시도 토픽은 기동 시 자동 생성되므로 브로커의 토픽 목록만 보면 된다.
 */
@Component
@Suppress("UnusedParameter")
class NamingListeners {

	/** 지수 백오프 + 인덱스 접미사, 재시도 토픽이 하나뿐인 경우. */
	@CustomRetryAndDLT(attempts = "2", backoff = Backoff(delay = 100, multiplier = 2.0))
	@KafkaListener(topics = [SINGLE_RETRY], groupId = "g-name-a")
	fun a(payload: String) = Unit

	/** 지수 백오프 + 인덱스 접미사, 재시도 토픽이 둘. */
	@CustomRetryAndDLT(attempts = "3", backoff = Backoff(delay = 100, multiplier = 2.0))
	@KafkaListener(topics = [INDEXED], groupId = "g-name-b")
	fun b(payload: String) = Unit

	/** 접미사를 지연 값으로 붙이는 전략. */
	@CustomRetryAndDLT(
		attempts = "3",
		backoff = Backoff(delay = 100, multiplier = 2.0),
		topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_DELAY_VALUE,
	)
	@KafkaListener(topics = [DELAY_SUFFIXED], groupId = "g-name-c")
	fun c(payload: String) = Unit

	/** 고정 간격 + SINGLE_TOPIC(우리 기본값). 간격이 모두 같아 한 토픽으로 합쳐진다. */
	@CustomRetryAndDLT(
		attempts = "4",
		backoff = Backoff(delay = 100, multiplier = 1.0),
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
	)
	@KafkaListener(topics = [FIXED_SINGLE], groupId = "g-name-d")
	fun d(payload: String) = Unit

	/** 고정 간격 + MULTIPLE_TOPICS. 간격이 같아도 시도마다 토픽을 나눈다. */
	@CustomRetryAndDLT(
		attempts = "4",
		backoff = Backoff(delay = 100, multiplier = 1.0),
		sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.MULTIPLE_TOPICS,
	)
	@KafkaListener(topics = [FIXED_MULTIPLE], groupId = "g-name-e")
	fun e(payload: String) = Unit

	/** 원본 토픽 이름에 이미 하이픈이 들어 있는 경우. */
	@CustomRetryAndDLT(attempts = "3", backoff = Backoff(delay = 100, multiplier = 2.0))
	@KafkaListener(topics = [HYPHENATED], groupId = "g-name-f")
	fun f(payload: String) = Unit

	companion object {
		const val SINGLE_RETRY = "name-a"
		const val INDEXED = "name-b"
		const val DELAY_SUFFIXED = "name-c"
		const val FIXED_SINGLE = "name-d"
		const val FIXED_MULTIPLE = "name-e"
		const val HYPHENATED = "name-f-order-events"

		val ALL = listOf(SINGLE_RETRY, INDEXED, DELAY_SUFFIXED, FIXED_SINGLE, FIXED_MULTIPLE, HYPHENATED)
	}
}
