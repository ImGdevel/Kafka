package com.study.kafka.retry

import org.springframework.core.annotation.AliasFor
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.DltStrategy
import org.springframework.kafka.retrytopic.ExceptionBasedDltDestination
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.retry.annotation.Backoff
import kotlin.reflect.KClass

/**
 * `@RetryableTopic`을 감싸는 프로젝트 표준 재시도/DLT 애노테이션.
 *
 * 동작 원리
 * - Spring Kafka의 `RetryTopicConfigurationProvider`는 리스너 메서드에서
 *   `MergedAnnotations.from(element, TYPE_HIERARCHY).get(RetryableTopic::class)` 로 애노테이션을 찾는다.
 *   메타 애노테이션까지 탐색하고 `@AliasFor` 속성 오버라이드를 합성(synthesize)하므로,
 *   이 애노테이션만 붙여도 Spring Kafka는 `@RetryableTopic`이 붙은 것으로 인식한다.
 * - 아래 속성들은 전부 `@RetryableTopic`의 동명 속성에 대한 별칭이다. 즉 값은 그대로 위임된다.
 *
 * 커스텀 여지
 * - 기본값을 Spring Kafka 기본값이 아니라 `${app.kafka.retry.*}` 프로퍼티 플레이스홀더로 두었다.
 *   Spring Kafka는 String 속성을 `resolveEmbeddedValue` → SpEL 순으로 평가하므로
 *   코드 수정 없이 `application.yml`에서 조직 표준값을 바꿀 수 있다.
 * - [owner], [alertOnDlt]는 `@RetryableTopic`에 없는 우리 확장 속성이다.
 *   Spring Kafka는 무시하고, [CustomRetryDltAttributes]로 읽어 우리 코드에서 사용한다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@RetryableTopic
annotation class CustomRetryAndDLT(

	/** 최초 시도를 포함한 총 처리 횟수. `@RetryableTopic.attempts` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "attempts")
	val attempts: String = "\${app.kafka.retry.attempts:3}",

	/** 재시도 백오프. `@RetryableTopic.backoff` 별칭. 기본값을 지수 백오프로 바꿔 둔다. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "backoff")
	val backoff: Backoff = Backoff(
		delayExpression = "\${app.kafka.retry.backoff.delay:1000}",
		multiplierExpression = "\${app.kafka.retry.backoff.multiplier:2.0}",
		maxDelayExpression = "\${app.kafka.retry.backoff.max-delay:10000}",
	),

	/** 전체 재시도 타임아웃(ms). `@RetryableTopic.timeout` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "timeout")
	val timeout: String = "",

	/** 재시도/DLT 발행에 쓸 KafkaTemplate 빈 이름. `@RetryableTopic.kafkaTemplate` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "kafkaTemplate")
	val kafkaTemplate: String = "",

	/** 재시도 토픽 리스너 컨테이너 팩토리 빈 이름. `@RetryableTopic.listenerContainerFactory` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "listenerContainerFactory")
	val listenerContainerFactory: String = "",

	/** 재시도/DLT 토픽 자동 생성 여부. `@RetryableTopic.autoCreateTopics` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "autoCreateTopics")
	val autoCreateTopics: String = "\${app.kafka.retry.auto-create-topics:true}",

	/** 자동 생성 시 파티션 수. `@RetryableTopic.numPartitions` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "numPartitions")
	val numPartitions: String = "\${app.kafka.retry.num-partitions:1}",

	/** 자동 생성 시 복제 계수. -1이면 브로커 기본값. `@RetryableTopic.replicationFactor` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "replicationFactor")
	val replicationFactor: String = "\${app.kafka.retry.replication-factor:-1}",

	/** 재시도 대상 예외. 비어 있으면 전부 재시도. `@RetryableTopic.include` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "include")
	val include: Array<KClass<out Throwable>> = [],

	/** 재시도 제외 예외(즉시 DLT). `@RetryableTopic.exclude` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "exclude")
	val exclude: Array<KClass<out Throwable>> = [],

	/** 재시도 대상 예외 FQCN. `@RetryableTopic.includeNames` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "includeNames")
	val includeNames: Array<String> = [],

	/** 재시도 제외 예외 FQCN. `@RetryableTopic.excludeNames` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "excludeNames")
	val excludeNames: Array<String> = [],

	/**
	 * include/exclude 판정 시 cause 체인까지 탐색할지. `@RetryableTopic.traversingCauses` 별칭.
	 *
	 * 주의: [include]/[exclude]/[includeNames]/[excludeNames]가 전부 비어 있는 상태에서 `true`로 두면
	 * `RetryTopicConfigurationBuilder.traversingCauses(true)`가 빈 규칙으로 분류기를 만들려다
	 * `IllegalArgumentException: Attempt to build classifier with empty rules`로 컨텍스트 기동이 실패한다.
	 * 그래서 기본값을 `false`로 두고, 예외 목록을 지정하는 리스너에서만 켜도록 한다.
	 */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "traversingCauses")
	val traversingCauses: String = "\${app.kafka.retry.traversing-causes:false}",

	/** 재시도 토픽 접미사. `@RetryableTopic.retryTopicSuffix` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "retryTopicSuffix")
	val retryTopicSuffix: String = "\${app.kafka.retry.retry-topic-suffix:-retry}",

	/** DLT 접미사. `@RetryableTopic.dltTopicSuffix` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "dltTopicSuffix")
	val dltTopicSuffix: String = "\${app.kafka.retry.dlt-topic-suffix:-dlt}",

	/** 예외 종류별 DLT 분기. `@RetryableTopic.exceptionBasedDltRouting` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "exceptionBasedDltRouting")
	val exceptionBasedDltRouting: Array<ExceptionBasedDltDestination> = [],

	/** 재시도 토픽 이름 생성 전략. `@RetryableTopic.topicSuffixingStrategy` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "topicSuffixingStrategy")
	val topicSuffixingStrategy: TopicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,

	/** 동일 간격 구간의 토픽 재사용 전략. `@RetryableTopic.sameIntervalTopicReuseStrategy` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "sameIntervalTopicReuseStrategy")
	val sameIntervalTopicReuseStrategy: SameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,

	/** DLT 처리 실패 시 전략. `@RetryableTopic.dltStrategy` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "dltStrategy")
	val dltStrategy: DltStrategy = DltStrategy.FAIL_ON_ERROR,

	/** DLT 핸들러 컨테이너 자동 시작 여부. `@RetryableTopic.autoStartDltHandler` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "autoStartDltHandler")
	val autoStartDltHandler: String = "",

	/** 재시도 토픽 리스너 동시성. `@RetryableTopic.concurrency` 별칭. */
	@get:AliasFor(annotation = RetryableTopic::class, attribute = "concurrency")
	val concurrency: String = "",

	// --- 여기부터는 @RetryableTopic에 없는 우리 확장 속성 ---

	/** 이 컨슈머의 담당 팀/채널. DLT 적재 시 누구에게 알릴지 판단하는 데 쓴다. */
	val owner: String = "",

	/** DLT 도달 시 알림 발송 여부. */
	val alertOnDlt: Boolean = true,
)
