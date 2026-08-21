package com.study.kafka.retry

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * `@CustomRetryAndDLT` 확장 속성 소비에 필요한 빈들.
 *
 * 이 모듈은 컴포넌트 스캔 대상이 아닌 라이브러리이므로, 쓰는 쪽에서 `@Import(CustomRetryDltConfiguration::class)`로 명시적으로 들여온다.
 * 알림 채널을 바꾸려면 [DeadLetterAlerter] 구현을 `@Primary`로 등록하면 된다.
 *
 * [CustomRetryTopicConfiguration]까지 함께 들여오므로 `@EnableKafkaRetryTopic`은 붙이지 않는다.
 *
 * DLT 발행을 EOS로 만들려면 애플리케이션에 아래 두 줄을 넣으면 된다. 모듈 쪽 코드 변경은 필요 없다.
 * ```yaml
 * spring.kafka.producer.transaction-id-prefix: my-app-tx-   # 트랜잭션 프로듀서 + KafkaTransactionManager
 * spring.kafka.consumer.isolation-level: read-committed      # abort 된 레코드를 걸러냄
 * ```
 * 둘 중 하나만 넣으면 EOS가 성립하지 않는다. [DltTransactionInspector]가 기동 시 그 상태를 경고한다.
 */
@Configuration(proxyBeanMethods = false)
@Import(CustomRetryTopicConfiguration::class)
class CustomRetryDltConfiguration {

	@Bean
	fun customRetryDltPolicyRegistry(beanFactory: ConfigurableListableBeanFactory) =
		CustomRetryDltPolicyRegistry(beanFactory)

	@Bean
	fun loggingDeadLetterAlerter(): DeadLetterAlerter = LoggingDeadLetterAlerter()

	@Bean
	fun deadLetterNotifier(registry: CustomRetryDltPolicyRegistry, alerter: DeadLetterAlerter) =
		DeadLetterNotifier(registry, alerter)

	@Bean
	fun dltTransactionInspector(
		kafkaOperations: ObjectProvider<KafkaOperations<*, *>>,
		consumerFactory: ObjectProvider<ConsumerFactory<*, *>>,
	) = DltTransactionInspector(kafkaOperations, consumerFactory)
}
