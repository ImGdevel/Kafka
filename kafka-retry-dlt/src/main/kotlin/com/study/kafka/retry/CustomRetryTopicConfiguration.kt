package com.study.kafka.retry

import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport

/**
 * 블로킹 재시도를 `@CustomRetryAndDLT` 단위로 제어하기 위한 재시도 토픽 인프라 설정.
 *
 * `@EnableKafkaRetryTopic`을 쓰면 안 된다. 그건 `@Import(RetryTopicConfigurationSupport)`라서
 * 이 클래스와 같이 두면 `RetryTopicConfigurationSupport` 빈이 두 개가 되고,
 * Spring Kafka는 예외가 아니라 경고만 남긴다
 * ("Only one RetryTopicConfigurationSupport object expected, found N; this may result in unexpected behavior").
 * 조용히 잘못 동작하므로 둘 중 하나만 쓴다.
 *
 * 동작 방식
 * - [configureBlockingRetries]는 게이트만 연다. `DefaultErrorHandler`는 `defaultFalse()`로 잠겨 있어서
 *   여기서 예외를 등록하지 않으면 블로킹 재시도가 아예 일어나지 않는다.
 * - 실제 횟수는 [configureCustomizers]가 물리는 `backOffFunction`이 레코드의 토픽을 보고 정한다.
 *   그래서 리스너마다 다른 블로킹 횟수를 줄 수 있다.
 * - 정책이 없는 토픽에도 `FixedBackOff(0, 0)`을 돌려준다. `null`을 돌려주면
 *   `DefaultErrorHandler` 기본값 `FixedBackOff(0, 9)`로 떨어져 간격 없이 10회 반복한다.
 */
@Configuration(proxyBeanMethods = false)
class CustomRetryTopicConfiguration : RetryTopicConfigurationSupport() {

	private lateinit var applicationContext: ApplicationContext

	@Throws(BeansException::class)
	override fun setApplicationContext(applicationContext: ApplicationContext) {
		this.applicationContext = applicationContext
		super.setApplicationContext(applicationContext)
	}

	override fun configureBlockingRetries(blockingRetries: BlockingRetriesConfigurer) {
		// Exception 전체를 게이트에 등록한다. 실제 적용 여부는 backOffFunction이 정책으로 판단한다.
		// 표준 fatal 예외(역직렬화 실패 등)는 더 구체적인 분류가 우선하므로 여전히 재시도되지 않는다.
		blockingRetries.retryOn(Exception::class.java)
	}

	override fun configureCustomizers(customizersConfigurer: CustomizersConfigurer) {
		customizersConfigurer.customizeErrorHandler { errorHandler ->
			errorHandler.setBackOffFunction { record, exception ->
				// 레지스트리는 모든 싱글턴이 만들어진 뒤에 스캔한다. 이 람다는 실패 시점에만 실행되므로 늦게 꺼내도 안전하다.
				policyRegistry().blockingBackOffFor(record.topic(), exception)
			}
		}
	}

	private fun policyRegistry(): CustomRetryDltPolicyRegistry =
		applicationContext.getBean(CustomRetryDltPolicyRegistry::class.java)
}
