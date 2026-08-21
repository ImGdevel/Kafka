package com.study.kafka.retry.chain

import com.study.kafka.retry.CustomRetryDltConfiguration
import com.study.kafka.retry.DeadLetterAlerter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 재시도 체인 검증 전용 부트 애플리케이션.
 *
 * 재시도 토픽 인프라는 `CustomRetryDltConfiguration`이 함께 들여오는 `CustomRetryTopicConfiguration`이 등록한다.
 * `@EnableKafkaRetryTopic`을 같이 붙이면 `RetryTopicConfigurationSupport` 빈이 두 개가 되어 경고만 남고
 * 조용히 잘못 동작하므로 붙이지 않는다.
 */
@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
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

	/** 기본 `LoggingDeadLetterAlerter` 대신 알림을 기록해 검증할 수 있게 갈아 끼운다. */
	@Bean
	@Primary
	fun recordingDeadLetterAlerter(recorder: RetryChainRecorder) =
		DeadLetterAlerter { alert -> recorder.recordAlert(alert) }
}
