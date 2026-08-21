package com.study.kafka.retry

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * `@CustomRetryAndDLT`가 붙은 리스너를 훑어 [CustomRetryDltPolicy]를 DLT 토픽 이름으로 색인한다.
 *
 * `BeanPostProcessor`가 아니라 [SmartInitializingSingleton]인 이유:
 * BPP는 컨테이너 기동 아주 이른 시점에 만들어져 다른 빈을 주입받으면 auto-proxy 대상에서 빠지는 부작용이 있다.
 * 모든 싱글턴이 만들어진 뒤 한 번만 훑으면 충분하다.
 *
 * 한계
 * - `@KafkaListener(topics = ...)`만 본다. `topicPattern`/`topicPartitions`는 색인하지 않는다.
 * - 클래스 레벨 `@KafkaListener` + `@KafkaHandler` 조합은 대상이 아니다.
 * - 접미사/토픽에 SpEL(`#{...}`)을 쓰면 해석하지 않는다. 프로퍼티 플레이스홀더(`${...}`)만 해석한다.
 */
class CustomRetryDltPolicyRegistry(
	private val beanFactory: ConfigurableListableBeanFactory,
) : SmartInitializingSingleton {

	private val log = LoggerFactory.getLogger(javaClass)
	private val byDltTopic = ConcurrentHashMap<String, CustomRetryDltPolicy>()

	override fun afterSingletonsInstantiated() {
		beanFactory.beanDefinitionNames.forEach { beanName ->
			val type = runCatching { beanFactory.getType(beanName) }.getOrNull() ?: return@forEach
			ReflectionUtils.doWithMethods(ClassUtils.getUserClass(type)) { method -> register(method) }
		}
		log.info("@CustomRetryAndDLT 정책 {}건 등록: {}", byDltTopic.size, byDltTopic.keys.sorted())
	}

	/** DLT 토픽 이름으로 정책을 찾는다. 정확히 일치하는 색인이 없으면 원본 토픽 접두사가 가장 긴 정책을 고른다. */
	fun findByDltTopic(dltTopic: String): CustomRetryDltPolicy? =
		byDltTopic[dltTopic] ?: byDltTopic.values
			.filter { policy -> policy.topics.any { dltTopic.startsWith(it) } }
			.maxByOrNull { policy -> policy.topics.filter { dltTopic.startsWith(it) }.maxOf { it.length } }

	fun findByOriginalTopic(topic: String): CustomRetryDltPolicy? =
		byDltTopic.values.firstOrNull { topic in it.topics }

	fun policies(): List<CustomRetryDltPolicy> = byDltTopic.values.distinct()

	private fun register(method: java.lang.reflect.Method) {
		val annotation = CustomRetryDltAttributes.find(method) ?: return
		val listener = AnnotatedElementUtils.findMergedAnnotation(method, KafkaListener::class.java) ?: return

		val topics = listener.topics.map(::resolve).filter { it.isNotBlank() }
		if (topics.isEmpty()) {
			log.warn("@CustomRetryAndDLT가 붙었지만 topics를 읽을 수 없어 건너뛴다: {}", method)
			return
		}

		val policy = CustomRetryDltPolicy(
			topics = topics,
			dltTopicSuffix = resolve(annotation.dltTopicSuffix),
			owner = annotation.owner,
			alertOnDlt = annotation.alertOnDlt,
			listenerId = "${method.declaringClass.simpleName}#${method.name}",
		)
		policy.dltTopics.forEach { byDltTopic[it] = policy }
	}

	private fun resolve(value: String): String = beanFactory.resolveEmbeddedValue(value) ?: value
}
