package com.study.kafka.retry.chain

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.kafka.annotation.EnableKafkaRetryTopic
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 재시도 체인 검증 전용 부트 애플리케이션.
 *
 * `@EnableKafkaRetryTopic`이 없으면 `@RetryableTopic`(및 이를 감싼 `@CustomRetryAndDLT`)을
 * 처리할 `RetryTopicConfigurer` 인프라가 등록되지 않아 재시도 토픽이 만들어지지 않는다.
 */
@SpringBootApplication
@EnableKafkaRetryTopic
class RetryChainTestApplication {

	@Bean
	fun retryChainRecorder() = RetryChainRecorder()

	/**
	 * 재시도 토픽은 백오프 시간 동안 파티션을 pause 했다가 재개하는 방식이라 스케줄러가 필요하다.
	 * 이 빈이 없으면 `RetryTopicConfigurationSupport`가
	 * "Either a RetryTopicSchedulerWrapper or TaskScheduler bean is required"로 기동을 막는다.
	 */
	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })
}
