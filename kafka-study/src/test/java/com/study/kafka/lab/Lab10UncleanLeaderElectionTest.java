package com.study.kafka.lab;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 10 — 언클린 리더 선출 설정 제어 (7장)
 *
 * 검증 명제: "토픽 레벨 unclean.leader.election.enable이 브로커 기본값을 오버라이드하는가?"
 *
 * Q1. 브로커 기본값: unclean.leader.election.enable의 브로커 기본값은 false인가?
 * Q2. 토픽 레벨 오버라이드: 토픽별로 true/false를 개별 설정할 수 있는가?
 * Q3. 동적 설정 변경: 운영 중 incrementalAlterConfigs()로 값을 바꿀 수 있는가?
 *
 * 언클린 리더 선출이란:
 *  ISR에 없는(out-of-sync) 레플리카가 리더가 되는 것.
 *  - enable=false(기본): 파티션이 오프라인되더라도 데이터 일관성 유지
 *  - enable=true: 가용성 우선, 그러나 out-of-sync 레플리카가 리더가 되면
 *    복제되지 않은 메시지가 손실되고 동일 오프셋에 다른 데이터가 기록될 수 있다
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab10UncleanLeaderElectionTest --info
 */
@Tag("lab")
@DisplayName("Lab 10 — 언클린 리더 선출 설정 제어")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab10UncleanLeaderElectionTest {

    private static final String CONFIG_KEY = "unclean.leader.election.enable";
    private static final String TOPIC_UNCLEAN_TRUE  = "lab10-unclean-true";
    private static final String TOPIC_UNCLEAN_FALSE = "lab10-unclean-false";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 10: 언클린 리더 선출 설정 제어");
        System.out.println("  핵심: 가용성(true) vs 일관성(false)의 트레이드오프");
        System.out.println("=".repeat(62));
        createTopicWithConfig(TOPIC_UNCLEAN_TRUE,  1, (short) 3,
                Map.of(CONFIG_KEY, "true"));
        createTopicWithConfig(TOPIC_UNCLEAN_FALSE, 1, (short) 3,
                Map.of(CONFIG_KEY, "false"));
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_UNCLEAN_TRUE);
        deleteTopic(TOPIC_UNCLEAN_FALSE);
    }

    /**
     * Q1. 브로커 기본값 확인 — unclean.leader.election.enable의 기본값은 false인가?
     *
     * Kafka 0.11.0부터 기본값이 true → false로 변경되었다.
     * 기본값 false는 "데이터 일관성 우선" 정책을 의미한다.
     * (파티션이 오프라인되는 한이 있어도 데이터 손실은 막는다)
     *
     * 브로커 레벨 설정 조회 방법:
     *  ConfigResource(Type.BROKER, brokerId)로 describeConfigs() 호출
     */
    @Test
    @Order(1)
    @DisplayName("Q1: 브로커 기본 설정 unclean.leader.election.enable=false를 확인한다")
    void broker_default_unclean_leader_election_is_false() throws Exception {
        System.out.println("\n  Q1: 브로커 기본값 확인");
        System.out.println("  → AdminClient.describeConfigs(BROKER resource)로 설정 조회");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            // 클러스터의 브로커 ID 목록 조회
            Collection<org.apache.kafka.common.Node> brokers =
                    admin.describeCluster().nodes().get();

            System.out.println();
            System.out.printf("  %-12s %-40s %-10s%n", "브로커 ID", CONFIG_KEY, "소스");
            System.out.println("  " + "-".repeat(64));

            String firstBrokerValue = null;
            for (org.apache.kafka.common.Node broker : brokers) {
                ConfigResource resource = new ConfigResource(ConfigResource.Type.BROKER,
                        String.valueOf(broker.id()));
                Config config = admin.describeConfigs(List.of(resource))
                        .all().get().get(resource);

                ConfigEntry entry = config.get(CONFIG_KEY);
                String value = entry != null ? entry.value() : "(없음)";
                String source = entry != null ? entry.source().toString() : "-";
                System.out.printf("  broker-%-5d %-40s %-10s%n", broker.id(), value, source);
                if (firstBrokerValue == null) firstBrokerValue = value;
            }

            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - DEFAULT_CONFIG: 브로커 server.properties에 명시 없음 → Kafka 기본값 사용");
            System.out.println("  - false(기본): ISR에 없는 레플리카는 리더 선출 대상에서 제외");
            System.out.println("  - true: ISR 외 레플리카도 리더 선출 가능 → 가용성↑ 일관성↓");
            System.out.println("  - Kafka 0.11.0+에서 기본값이 true→false로 변경됨");
            printSeparator();

            // 기본값이 false임을 검증 (DEFAULT_CONFIG 또는 STATIC_BROKER_CONFIG 소스로 false)
            assertThat(firstBrokerValue)
                    .as("브로커 기본 unclean.leader.election.enable이 false여야 한다")
                    .isEqualTo("false");
        }
    }

    /**
     * Q2. 토픽 레벨 오버라이드 — 토픽별로 서로 다른 값을 설정할 수 있는가?
     *
     * 브로커 기본값이 false여도 토픽 레벨에서 true로 오버라이드할 수 있다.
     * 이 기능으로 "신뢰성이 중요한 토픽"과 "가용성이 중요한 토픽"을 다르게 관리한다.
     *
     * 예시:
     *  - 결제 토픽: enable=false (데이터 손실 절대 안 됨)
     *  - 로그 토픽: enable=true  (일부 손실 허용, 대신 항상 쓸 수 있어야 함)
     */
    @Test
    @Order(2)
    @DisplayName("Q2: 토픽 레벨 설정으로 브로커 기본값을 오버라이드할 수 있다")
    void topic_level_config_overrides_broker_default() throws Exception {
        System.out.println("\n  Q2: 토픽 레벨 unclean.leader.election.enable 설정 확인");
        System.out.println("  → 토픽 A(true), 토픽 B(false)의 effective config 비교");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            ConfigResource resourceTrue  = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC_UNCLEAN_TRUE);
            ConfigResource resourceFalse = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC_UNCLEAN_FALSE);

            Map<ConfigResource, Config> configs = admin
                    .describeConfigs(List.of(resourceTrue, resourceFalse))
                    .all().get();

            System.out.println();
            System.out.printf("  %-30s %-8s %-25s%n", "토픽", "설정값", "소스");
            System.out.println("  " + "-".repeat(64));

            String valueTrue  = extractConfigValue(configs, resourceTrue);
            String sourcesTrue  = extractConfigSource(configs, resourceTrue);
            String valueFalse = extractConfigValue(configs, resourceFalse);
            String sourcesFalse = extractConfigSource(configs, resourceFalse);

            System.out.printf("  %-30s %-8s %-25s%n", TOPIC_UNCLEAN_TRUE,  valueTrue,  sourcesTrue);
            System.out.printf("  %-30s %-8s %-25s%n", TOPIC_UNCLEAN_FALSE, valueFalse, sourcesFalse);

            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - DYNAMIC_TOPIC_CONFIG: 토픽 생성 시 명시적으로 지정된 값");
            System.out.println("  - 토픽 레벨 설정이 브로커 기본값보다 우선한다");
            System.out.println("  - 토픽 레벨 설정을 지우면 브로커 기본값(false)으로 복원된다");
            printSeparator();

            assertThat(valueTrue)
                    .as("TOPIC_UNCLEAN_TRUE의 설정값이 true여야 한다")
                    .isEqualTo("true");
            assertThat(valueFalse)
                    .as("TOPIC_UNCLEAN_FALSE의 설정값이 false여야 한다")
                    .isEqualTo("false");
        }
    }

    /**
     * Q3. 동적 설정 변경 — 토픽 재시작 없이 설정을 바꿀 수 있는가?
     *
     * Kafka AdminClient의 incrementalAlterConfigs()를 사용하면
     * 브로커/토픽을 재시작하지 않고 설정을 실시간으로 변경할 수 있다.
     *
     * 이 동작은 운영 중 긴급하게 정책을 바꿔야 할 때 유용하다:
     *  "지금 당장은 가용성이 더 중요하다 → enable=true로 임시 변경"
     *  "복구 완료 후 다시 일관성 우선으로 → enable=false로 복원"
     */
    @Test
    @Order(3)
    @DisplayName("Q3: incrementalAlterConfigs()로 운영 중 설정을 동적으로 변경할 수 있다")
    void dynamic_config_change_via_incremental_alter_configs() throws Exception {
        System.out.println("\n  Q3: 동적 설정 변경 (incrementalAlterConfigs)");
        System.out.println("  → TOPIC_UNCLEAN_TRUE의 값을 true→false로 변경 후 재조회");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC_UNCLEAN_TRUE);

            // 변경 전 확인
            String before = extractConfigValue(
                    admin.describeConfigs(List.of(resource)).all().get(), resource);
            System.out.printf("%n  변경 전: %s = %s%n", CONFIG_KEY, before);

            // true → false로 변경
            AlterConfigOp op = new AlterConfigOp(
                    new ConfigEntry(CONFIG_KEY, "false"),
                    AlterConfigOp.OpType.SET
            );
            admin.incrementalAlterConfigs(Map.of(resource, List.of(op))).all().get();
            Thread.sleep(500); // 설정 전파 대기

            // 변경 후 확인
            String after = extractConfigValue(
                    admin.describeConfigs(List.of(resource)).all().get(), resource);
            System.out.printf("  변경 후: %s = %s%n%n", CONFIG_KEY, after);

            System.out.println("  결과 해석:");
            System.out.println("  - incrementalAlterConfigs: 토픽/브로커 재시작 없이 즉시 적용");
            System.out.println("  - OpType.SET: 값을 지정된 값으로 설정");
            System.out.println("  - OpType.DELETE: 토픽 레벨 설정 제거 → 브로커 기본값 복원");
            System.out.println("  - 변경된 설정은 이후 리더 선출 시점부터 적용된다");
            printSeparator();

            // 변경 전 true, 변경 후 false 검증
            assertThat(before).as("변경 전 값이 true여야 한다").isEqualTo("true");
            assertThat(after).as("변경 후 값이 false여야 한다").isEqualTo("false");
        }
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private String extractConfigValue(Map<ConfigResource, Config> configs, ConfigResource resource) {
        ConfigEntry entry = configs.get(resource).get(CONFIG_KEY);
        return entry != null ? entry.value() : "(없음)";
    }

    private String extractConfigSource(Map<ConfigResource, Config> configs, ConfigResource resource) {
        ConfigEntry entry = configs.get(resource).get(CONFIG_KEY);
        return entry != null ? entry.source().toString() : "-";
    }
}
