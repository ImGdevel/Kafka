package com.study.kafka.retry.startupfail

import com.study.kafka.retry.CustomRetryAndDLT
import com.study.kafka.retry.CustomRetryDltConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.retrytopic.RetryTopicSchedulerWrapper
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component

/**
 * 기동 실패를 확인하기 위한 별도 애플리케이션.
 *
 * 다른 테스트 컨텍스트에 섞이면 그쪽 기동까지 막으므로 형제 패키지에 따로 둔다.
 * 컴포넌트 스캔이 이 패키지만 훑도록 `@SpringBootApplication`을 여기에 붙였다.
 */
@SpringBootApplication
@Import(CustomRetryDltConfiguration::class)
class StartupFailApplication {

	@Bean
	fun retryTopicSchedulerWrapper() = RetryTopicSchedulerWrapper(ThreadPoolTaskScheduler().apply { initialize() })
}

/**
 * 논블로킹 재시도를 쓰면서 `autoCreateTopics=false`인데 재시도 토픽도 DLT도 브로커에 없는 리스너.
 *
 * 이 조합은 그대로 뜨면 첫 장애 메시지에서 파티션이 조용히 멈춘다. 기동을 막아야 한다.
 */
@Component
class StartupFailListener {

	@CustomRetryAndDLT(attempts = "3", autoCreateTopics = "false")
	@KafkaListener(id = LISTENER_ID, topics = [TOPIC], groupId = "g-startup-fail")
	fun handle(payload: String) = Unit

	@DltHandler
	fun dlt(payload: String) = Unit

	companion object {
		const val TOPIC = "startupfail-orders"
		const val LISTENER_ID = "startup-fail-listener"
	}
}
