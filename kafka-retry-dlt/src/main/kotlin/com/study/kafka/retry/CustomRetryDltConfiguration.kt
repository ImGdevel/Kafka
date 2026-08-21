package com.study.kafka.retry

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `@CustomRetryAndDLT` 확장 속성 소비에 필요한 빈들.
 *
 * 이 모듈은 컴포넌트 스캔 대상이 아닌 라이브러리이므로, 쓰는 쪽에서 `@Import(CustomRetryDltConfiguration::class)`로 명시적으로 들여온다.
 * 알림 채널을 바꾸려면 [DeadLetterAlerter] 구현을 `@Primary`로 등록하면 된다.
 */
@Configuration(proxyBeanMethods = false)
class CustomRetryDltConfiguration {

	@Bean
	fun customRetryDltPolicyRegistry(beanFactory: ConfigurableListableBeanFactory) =
		CustomRetryDltPolicyRegistry(beanFactory)

	@Bean
	fun loggingDeadLetterAlerter(): DeadLetterAlerter = LoggingDeadLetterAlerter()

	@Bean
	fun deadLetterNotifier(registry: CustomRetryDltPolicyRegistry, alerter: DeadLetterAlerter) =
		DeadLetterNotifier(registry, alerter)
}
