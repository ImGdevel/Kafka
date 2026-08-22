package com.study.kafka.retry

import org.apache.kafka.clients.admin.AdminClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.SmartLifecycle
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaAdmin
import java.util.concurrent.TimeUnit

/**
 * 어떤 토픽이 브로커에 반드시 있어야 하는지 판정하는 규칙. 순수 함수라 브로커 없이 검증할 수 있다.
 */
object RetryTopicPresenceRules {

	/**
	 * 검사 대상은 리스너가 실제로 구독 중인 토픽 가운데 원본이 아닌 것들, 즉 재시도 토픽과 DLT다.
	 * 이름을 우리가 계산하지 않고 컨테이너에서 가져오는 이유가 있다. 재시도 토픽 이름은
	 * `topicSuffixingStrategy`와 `sameIntervalTopicReuseStrategy`, 그리고 백오프 간격에 따라
	 * `-retry`가 되기도 `-retry-0`이 되기도 `-retry-100`이 되기도 한다. 규칙을 복제하면 언젠가 어긋난다.
	 *
	 * 원본 토픽은 검사하지 않는다. 그건 이 애노테이션이 만들어 내는 토픽이 아니라 사용자의 입력이다.
	 *
	 * `autoCreateTopics=true`인 정책은 Spring이 기동 시 직접 만들어 주므로 검사 대상이 아니다.
	 * 블로킹 전용(`attempts=1`) 구성은 애초에 재시도 토픽 컨테이너가 없어 자연히 빠지고,
	 * `dltStrategy=NO_DLT`도 DLT 컨테이너가 없어 자연히 빠진다.
	 */
	fun requiredTopics(
		subscribedTopics: Collection<String>,
		mainTopics: Set<String>,
		policyFor: (String) -> CustomRetryDltPolicy?,
	): Map<String, CustomRetryDltPolicy> =
		subscribedTopics
			.asSequence()
			.distinct()
			.filter { it !in mainTopics }
			.mapNotNull { topic -> policyFor(topic)?.takeUnless { it.autoCreateTopics }?.let { topic to it } }
			.toMap()

	fun missingTopics(
		required: Map<String, CustomRetryDltPolicy>,
		existingTopics: Set<String>,
	): Map<String, CustomRetryDltPolicy> = required.filterKeys { it !in existingTopics }

	fun describe(missing: Map<String, CustomRetryDltPolicy>): String {
		val detail = missing.entries
			.sortedBy { it.key }
			.joinToString(separator = "\n") { (topic, policy) -> "  - $topic (${policy.listenerId})" }

		return """
			|@CustomRetryAndDLT: autoCreateTopics=false 인데 브로커에 없는 토픽이 있다.
			|$detail
			|이 상태로 기동하면 처리에 실패한 첫 메시지가 다음 홉으로 넘어가지 못해
			|해당 파티션이 무한 재처리에 빠진다. 예외도 뜨지 않고 컨슈머 랙으로만 드러난다.
			|토픽을 미리 만들거나 autoCreateTopics 를 켜라.
		""".trimMargin()
	}
}

/**
 * `autoCreateTopics=false`인 리스너의 재시도 토픽과 DLT가 브로커에 있는지 기동 시점에 확인한다.
 *
 * 없으면 기동을 실패시킨다. 이건 트레이드오프가 아니라 설정 사고다. 그대로 뜨면 배포는 초록불인데
 * 첫 장애 메시지에서 파티션이 조용히 멈춘다. 토픽 이름은 런타임에 해석되는 문자열이라
 * 컴파일이나 빌드 시점에 걸러낼 방법이 없고, 기동 시점이 잡을 수 있는 가장 이른 지점이다.
 *
 * `autoCreateTopics=true`(기본값)인 정책은 Spring이 `KafkaAdmin`으로 직접 만들어 주므로 검사하지 않는다.
 * 그래서 이 검사는 토픽 생성 권한이 없어 `autoCreateTopics=false`로 운영하는 환경에서만 동작한다.
 *
 * `SmartInitializingSingleton`이 아니라 [SmartLifecycle]인 이유가 있다. 검사하려면 리스너 컨테이너가
 * 구독 중인 토픽 목록이 필요한데, 컨테이너는 `KafkaListenerAnnotationBeanPostProcessor`의
 * 싱글턴 초기화 콜백에서 만들어진다. 그 콜백 순서는 빈 정의 순서에 달려 있어 우리가 먼저 돌면 빈손이 된다.
 * 라이프사이클 시작 단계는 모든 싱글턴 초기화가 끝난 뒤이므로 컨테이너가 반드시 존재하고,
 * [getPhase]를 컨테이너보다 낮게 두어 컨테이너가 실제로 소비를 시작하기 전에 끼어든다.
 */
class RetryTopicPresenceValidator(
	private val policyRegistry: CustomRetryDltPolicyRegistry,
	private val endpointRegistry: ObjectProvider<KafkaListenerEndpointRegistry>,
	private val kafkaAdmin: ObjectProvider<KafkaAdmin>,
) : SmartLifecycle {

	private val log = LoggerFactory.getLogger(javaClass)

	@Volatile
	private var running = false

	/** 리스너 컨테이너 기본 phase 는 `Integer.MAX_VALUE - 100` 이다. 그보다 먼저 돌아야 한다. */
	override fun getPhase(): Int = Int.MIN_VALUE

	override fun start() {
		validate()
		running = true
	}

	override fun stop() {
		running = false
	}

	override fun isRunning(): Boolean = running

	/** 검증에 실패하면 [IllegalStateException]을 던진다. 테스트에서 직접 부를 수 있도록 공개해 둔다. */
	fun validate() {
		val required = RetryTopicPresenceRules.requiredTopics(
			subscribedTopics = subscribedTopics(),
			mainTopics = policyRegistry.policies().flatMap { it.topics }.toSet(),
			policyFor = policyRegistry::findByTopic,
		)
		if (required.isEmpty()) {
			return
		}

		val existing = existingTopics()
		if (existing == null) {
			log.warn("KafkaAdmin 이 없어 토픽 존재 검사를 건너뛴다. 대상={}", required.keys.sorted())
			return
		}

		val missing = RetryTopicPresenceRules.missingTopics(required, existing)
		if (missing.isNotEmpty()) {
			throw IllegalStateException(RetryTopicPresenceRules.describe(missing))
		}

		log.info("autoCreateTopics=false 토픽 {}건 존재 확인: {}", required.size, required.keys.sorted())
	}

	/**
	 * 리스너 컨테이너가 구독 중인 토픽. 대상 토픽이 브로커에 없어도 컨테이너는 만들어지고 구독까지 하므로
	 * 존재하지 않는 토픽의 이름도 여기서 그대로 얻을 수 있다.
	 */
	private fun subscribedTopics(): List<String> {
		val registry = endpointRegistry.ifAvailable ?: return emptyList()
		return registry.listenerContainers.flatMap { container ->
			container.containerProperties.topics?.toList() ?: emptyList()
		}
	}

	private fun existingTopics(): Set<String>? {
		val admin = kafkaAdmin.ifAvailable ?: return null
		return runCatching {
			AdminClient.create(admin.configurationProperties).use { client ->
				client.listTopics().names().get(TOPIC_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			}
		}.getOrElse { ex ->
			// 브로커에 못 붙는 것과 토픽이 없는 것은 다른 문제다. 여기서 기동을 막으면 원인을 오해하게 된다.
			log.warn("브로커에서 토픽 목록을 읽지 못해 존재 검사를 건너뛴다.", ex)
			null
		}
	}

	companion object {
		private const val TOPIC_LOOKUP_TIMEOUT_SECONDS = 15L
	}
}
