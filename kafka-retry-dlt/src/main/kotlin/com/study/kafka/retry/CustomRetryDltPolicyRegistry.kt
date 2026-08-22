package com.study.kafka.retry

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.FixedBackOff
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * `@CustomRetryAndDLT`가 붙은 리스너를 훑어 [CustomRetryDltPolicy]를 토픽 이름으로 색인한다.
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
	private val consumerFactory: ObjectProvider<ConsumerFactory<*, *>>? = null,
) : SmartInitializingSingleton {

	private val log = LoggerFactory.getLogger(javaClass)
	private val byTopic = ConcurrentHashMap<String, CustomRetryDltPolicy>()

	override fun afterSingletonsInstantiated() {
		beanFactory.beanDefinitionNames.forEach { beanName ->
			val type = runCatching { beanFactory.getType(beanName) }.getOrNull() ?: return@forEach
			ReflectionUtils.doWithMethods(ClassUtils.getUserClass(type)) { method -> register(method) }
		}
		log.info("@CustomRetryAndDLT 정책 {}건 등록: {}", policies().size, byTopic.keys.sorted())
	}

	/**
	 * 토픽 이름으로 정책을 찾는다.
	 *
	 * 원본/DLT 토픽은 색인에 그대로 들어 있고, 재시도 토픽(`orders-retry-0` 등)은 이름이 동적이라
	 * 원본 토픽 접두사가 가장 긴 정책으로 되짚는다.
	 */
	fun findByTopic(topic: String): CustomRetryDltPolicy? =
		byTopic[topic] ?: byTopic.values
			.filter { policy -> policy.topics.any { topic.startsWith(it) } }
			.maxByOrNull { policy -> policy.topics.filter { topic.startsWith(it) }.maxOf { it.length } }

	fun findByOriginalTopic(topic: String): CustomRetryDltPolicy? =
		byTopic.values.firstOrNull { topic in it.topics }

	fun policies(): List<CustomRetryDltPolicy> = byTopic.values.distinct()

	/**
	 * `DefaultErrorHandler.setBackOffFunction`에 물릴 진입점.
	 * 정책을 못 찾은 토픽이라도 `null`이 아니라 "재시도 0회"를 돌려준다.
	 */
	fun blockingBackOffFor(topic: String, exception: Throwable?): BackOff =
		findByTopic(topic)?.blockingBackOffFor(exception) ?: NO_BLOCKING_RETRY

	private fun register(method: Method) {
		val annotation = CustomRetryDltAttributes.find(method) ?: return
		val listener = AnnotatedElementUtils.findMergedAnnotation(method, KafkaListener::class.java) ?: return

		val topics = listener.topics.map(::resolve).filter { it.isNotBlank() }
		if (topics.isEmpty()) {
			log.warn("@CustomRetryAndDLT가 붙었지만 topics를 읽을 수 없어 건너뛴다: {}", method)
			return
		}

		val listenerId = "${method.declaringClass.simpleName}#${method.name}"
		val attempts = resolveInt(annotation.attempts, "attempts", listenerId)
		val blockingAttempts = resolveInt(annotation.blockingAttempts, "blockingAttempts", listenerId) ?: 1
		validate(attempts, blockingAttempts, listenerId)

		val policy = CustomRetryDltPolicy(
			topics = topics,
			dltTopicSuffix = resolve(annotation.dltTopicSuffix),
			owner = annotation.owner,
			alertOnDlt = annotation.alertOnDlt,
			attempts = attempts,
			blockingAttempts = blockingAttempts,
			blockingBackoffDelay = resolveInt(annotation.blockingBackoffDelay, "blockingBackoffDelay", listenerId)
				?.toLong() ?: DEFAULT_BLOCKING_DELAY_MILLIS,
			blockingRetryOn = annotation.blockingRetryOn.map { it.java },
			dltStrategy = annotation.dltStrategy,
			autoCreateTopics = resolveBoolean(annotation.autoCreateTopics, "autoCreateTopics", listenerId),
			listenerId = listenerId,
		)
		warnIfRisky(policy)
		policy.topics.forEach { byTopic[it] = policy }
		policy.dltTopics.forEach { byTopic[it] = policy }
	}

	/**
	 * 블로킹과 논블로킹을 동시에 켜면 총 시도 횟수가 두 값의 곱이 된다.
	 * 의도한 사람보다 실수한 사람이 많을 조합이라 기동 시점에 막는다.
	 */
	private fun validate(attempts: Int?, blockingAttempts: Int, listenerId: String) {
		require(blockingAttempts >= 1) {
			"$listenerId: blockingAttempts는 1 이상이어야 한다. (현재 $blockingAttempts)"
		}
		if (attempts != null && attempts > 1 && blockingAttempts > 1) {
			throw IllegalStateException(
				"$listenerId: attempts=$attempts, blockingAttempts=$blockingAttempts 를 함께 쓰면 " +
					"총 시도 횟수가 ${attempts * blockingAttempts}회가 된다. " +
					"블로킹 전용이면 attempts=1, 논블로킹 전용이면 blockingAttempts=1로 둬라.",
			)
		}
	}

	/**
	 * 기동을 막지는 않지만 조용히 넘기면 안 되는 조합들.
	 *
	 * 둘 다 프레임워크가 허용하는 정상 설정이라 예외로 막을 근거는 없다.
	 * 다만 증상이 늦게, 그리고 엉뚱한 모습으로 나타나기 때문에 기동 시점에 이름과 함께 남긴다.
	 */
	private fun warnIfRisky(policy: CustomRetryDltPolicy) {
		if (policy.discardsExhaustedMessages) {
			log.warn(
				"{}: dltStrategy=NO_DLT 이므로 재시도가 소진되면 메시지가 어디에도 남지 않고 폐기된다. topics={}",
				policy.listenerId,
				policy.topics,
			)
		}

		val maxPollInterval = maxPollIntervalMillis()
		if (policy.blockingAttempts > 1 && policy.blockingBackoffDelay >= maxPollInterval) {
			log.warn(
				"{}: blockingBackoffDelay={}ms 가 max.poll.interval.ms={}ms 이상이다. " +
					"재시도마다 'consumer poll timeout has expired'로 컨슈머가 그룹에서 이탈한다. " +
					"참고로 전체 블로킹 구간(delay x (attempts-1))은 상한을 넘어도 무방하다. " +
					"시도 사이에 poll()이 돌기 때문이다.",
				policy.listenerId,
				policy.blockingBackoffDelay,
				maxPollInterval,
			)
		}
	}

	private fun maxPollIntervalMillis(): Long =
		consumerFactory?.ifAvailable
			?.configurationProperties
			?.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)
			?.toString()
			?.toLongOrNull()
			?: DEFAULT_MAX_POLL_INTERVAL_MILLIS

	private fun resolve(value: String): String = beanFactory.resolveEmbeddedValue(value) ?: value

	/** 읽을 수 없으면 `@RetryableTopic` 기본값과 같은 true 로 본다. 검사를 덜 하는 쪽이 안전하다. */
	private fun resolveBoolean(value: String, attribute: String, listenerId: String): Boolean {
		val resolved = resolve(value)
		return resolved.trim().lowercase().toBooleanStrictOrNull() ?: run {
			log.warn("{}: {}=\"{}\" 를 boolean 으로 읽을 수 없어 true 로 본다.", listenerId, attribute, resolved)
			true
		}
	}

	private fun resolveInt(value: String, attribute: String, listenerId: String): Int? {
		val resolved = resolve(value)
		return resolved.toIntOrNull() ?: run {
			log.warn("{}: {}=\"{}\" 를 숫자로 읽을 수 없어 검증을 건너뛴다.", listenerId, attribute, resolved)
			null
		}
	}

	companion object {
		private const val DEFAULT_BLOCKING_DELAY_MILLIS = 500L

		/** Kafka 기본값. 컨슈머 설정에서 읽지 못했을 때 쓴다. */
		private const val DEFAULT_MAX_POLL_INTERVAL_MILLIS = 300_000L
		private val NO_BLOCKING_RETRY: BackOff = FixedBackOff(0L, 0L)
	}
}
