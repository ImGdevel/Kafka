package com.study.kafka.lab;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.ElectionNotNeededException;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 11 — 선호 리더 선출 (7장)
 *
 * 검증 명제: "electLeaders(PREFERRED) 호출 후 리더가 선호 리더(replica 목록의 첫 번째)와 일치하는가?"
 *
 * Q1. 선호 리더 식별: replica 목록의 첫 번째 브로커가 선호 리더인가?
 * Q2. 선호 리더 강제 선출: electLeaders(PREFERRED) 호출 후 리더 == 선호 리더인가?
 * Q3. 자동 균형 설정 확인: auto.leader.rebalance.enable과 leader.imbalance.check.interval.seconds
 *
 * 선호 리더(Preferred Leader)란:
 *  토픽 생성 시 Kafka가 각 파티션에 배정한 "원래" 리더 브로커.
 *  replica 목록의 첫 번째 브로커가 선호 리더이다.
 *  브로커 장애 후 복구 시 리더가 다른 브로커로 이동할 수 있는데,
 *  이를 선호 리더로 복원하는 것이 "선호 리더 선출(Preferred Leader Election)"이다.
 *
 * 왜 중요한가:
 *  카프카는 토픽 생성 시 파티션별 리더를 여러 브로커에 균등 분산한다.
 *  장애-복구 후 리더 편중이 발생하면 특정 브로커에 부하가 집중된다.
 *  선호 리더 선출로 원래 균등한 분산을 복원할 수 있다.
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab11PreferredLeaderElectionTest --info
 */
@Tag("lab")
@DisplayName("Lab 11 — 선호 리더 선출")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab11PreferredLeaderElectionTest {

    private static final String TOPIC = "lab11-preferred-leader";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 11: 선호 리더 선출 (Preferred Leader Election)");
        System.out.println("  파티션별 리더 부하 균등화 메커니즘 확인");
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 6, (short) 3); // 6 파티션 → 브로커당 2파티션 리더
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC);
    }

    /**
     * Q1. 선호 리더 식별 — replica 목록의 첫 번째 브로커가 선호 리더인가?
     *
     * Kafka AdminClient의 describeTopics()가 반환하는 TopicPartitionInfo에서
     * replicas() 목록의 첫 번째 노드가 해당 파티션의 선호 리더이다.
     *
     * 이 정보는 Kafka 메타데이터에 저장되며 파티션 수가 바뀌지 않는 한 변경되지 않는다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1: replica 목록의 첫 번째 브로커가 선호 리더이다")
    void first_replica_is_preferred_leader() throws Exception {
        System.out.println("\n  Q1: 선호 리더 식별");
        System.out.println("  → describeTopics()에서 replicas[0] = 선호 리더");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            TopicDescription desc = admin.describeTopics(List.of(TOPIC))
                    .allTopicNames().get().get(TOPIC);

            System.out.println();
            System.out.printf("  %-8s %-20s %-15s %-12s%n",
                    "파티션", "레플리카 순서", "선호 리더", "현재 리더");
            System.out.println("  " + "-".repeat(58));

            // 브로커별 선호 리더 파티션 수 집계
            Map<Integer, Integer> preferredLeaderCount = new TreeMap<>();

            for (TopicPartitionInfo p : desc.partitions()) {
                int preferredLeaderId = p.replicas().get(0).id();  // 첫 번째 replica
                int currentLeaderId  = p.leader().id();
                preferredLeaderCount.merge(preferredLeaderId, 1, Integer::sum);

                String replicaList = p.replicas().stream()
                        .map(n -> "b" + n.id())
                        .collect(Collectors.joining("→"));
                boolean isPreferred = (currentLeaderId == preferredLeaderId);

                System.out.printf("  %-8d %-20s %-15s %-12s%n",
                        p.partition(), replicaList,
                        "broker-" + preferredLeaderId,
                        "broker-" + currentLeaderId + (isPreferred ? " ✓" : " ≠선호"));
            }

            System.out.println();
            System.out.println("  브로커별 선호 리더 파티션 수:");
            preferredLeaderCount.forEach((brokerId, count) ->
                    System.out.printf("    broker-%-3d : %d 파티션%n", brokerId, count));

            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - replicas[0] = 선호 리더 (토픽 생성 시 카프카가 결정)");
            System.out.println("  - 6파티션 / 3브로커 → 브로커당 2파티션이 선호 리더로 균등 분산");
            System.out.println("  - 장애 복구 후 리더가 바뀌면 '선호 리더 ≠ 현재 리더' 상태가 됨");
            printSeparator();

            // 모든 파티션에 리더가 있어야 한다
            desc.partitions().forEach(p ->
                    assertThat(p.leader()).as("파티션 %d의 리더가 존재해야 한다", p.partition())
                            .isNotNull());
        }
    }

    /**
     * Q2. 선호 리더 강제 선출 — electLeaders(PREFERRED) 호출 후 리더 == 선호 리더인가?
     *
     * AdminClient.electLeaders(ElectionType.PREFERRED, partitions)를 호출하면
     * 컨트롤러가 각 파티션의 리더를 선호 리더(replicas[0])로 변경한다.
     *
     * - 현재 리더 == 선호 리더: no-op (이미 올바른 상태)
     * - 현재 리더 != 선호 리더: 선호 리더가 ISR에 있으면 리더 전환 수행
     *
     * 이 조작은 장애 복구 후 부하 균등화를 수동으로 트리거할 때 사용한다.
     * auto.leader.rebalance.enable=true이면 자동으로도 수행된다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: electLeaders(PREFERRED)로 선호 리더를 강제 선출한다")
    void elect_preferred_leaders_restores_balance() throws Exception {
        System.out.println("\n  Q2: 선호 리더 강제 선출");
        System.out.println("  → AdminClient.electLeaders(ElectionType.PREFERRED, ...)");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            TopicDescription descBefore = admin.describeTopics(List.of(TOPIC))
                    .allTopicNames().get().get(TOPIC);

            // 모든 파티션에 대해 선호 리더 선출 요청
            Set<TopicPartition> partitions = descBefore.partitions().stream()
                    .map(p -> new TopicPartition(TOPIC, p.partition()))
                    .collect(Collectors.toSet());

            System.out.println();
            System.out.println("  선호 리더 선출 전:");
            printLeaderStatus(descBefore);

            // 선호 리더 선출 실행
            // ElectionNotNeededException: 모든 파티션이 이미 선호 리더 상태 → no-op이므로 정상
            try {
                admin.electLeaders(ElectionType.PREFERRED, partitions).all().get();
            } catch (java.util.concurrent.ExecutionException e) {
                if (!(e.getCause() instanceof ElectionNotNeededException)) throw e;
                System.out.println("  (모든 파티션이 이미 선호 리더 상태 → ElectionNotNeededException = 정상)");
            }
            Thread.sleep(1000); // 리더 선출 완료 대기

            TopicDescription descAfter = admin.describeTopics(List.of(TOPIC))
                    .allTopicNames().get().get(TOPIC);

            System.out.println();
            System.out.println("  선호 리더 선출 후:");
            printLeaderStatus(descAfter);

            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - electLeaders(PREFERRED): 컨트롤러에 선호 리더 선출 요청");
            System.out.println("  - 이미 선호 리더이면 no-op → 오류 없이 정상 완료");
            System.out.println("  - 선호 리더가 ISR에 있어야 선출 가능 (out-of-sync이면 실패)");
            System.out.println("  - ElectionType.UNCLEAN: out-of-sync 레플리카도 선출 강제 (데이터 손실 가능)");
            printSeparator();

            // 선출 후 모든 파티션의 현재 리더 == 선호 리더 검증
            for (TopicPartitionInfo p : descAfter.partitions()) {
                int preferredLeaderId = p.replicas().get(0).id();
                int currentLeaderId  = p.leader().id();
                assertThat(currentLeaderId)
                        .as("파티션 %d의 리더가 선호 리더(broker-%d)여야 한다",
                                p.partition(), preferredLeaderId)
                        .isEqualTo(preferredLeaderId);
            }
        }
    }

    /**
     * Q3. 자동 균형 설정 확인 — auto.leader.rebalance.enable과 주기 설정
     *
     * 수동 electLeaders() 대신, Kafka는 주기적으로 자동으로 선호 리더를 복원할 수 있다.
     * - auto.leader.rebalance.enable=true(기본): 주기적으로 리더 불균형 감지 및 자동 균형화
     * - leader.imbalance.check.interval.seconds=300(기본 5분): 균형 확인 주기
     * - leader.imbalance.per.broker.percentage=10(기본): 10% 이상 불균형 시 재조정
     *
     * 자동 균형화는 Controller가 수행한다. 균형화 도중 리더 전환이 발생하므로
     * 일시적인 지연이 생길 수 있다.
     */
    @Test
    @Order(3)
    @DisplayName("Q3: auto.leader.rebalance.enable 브로커 설정을 확인한다")
    void auto_leader_rebalance_broker_settings() throws Exception {
        System.out.println("\n  Q3: 자동 선호 리더 재균형 설정 확인");
        System.out.println("  → 브로커 레벨 leader.imbalance 관련 설정 조회");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        List<String> targetConfigs = List.of(
                "auto.leader.rebalance.enable",
                "leader.imbalance.check.interval.seconds",
                "leader.imbalance.per.broker.percentage"
        );

        try (AdminClient admin = AdminClient.create(props)) {
            Collection<org.apache.kafka.common.Node> brokers =
                    admin.describeCluster().nodes().get();

            org.apache.kafka.common.Node firstBroker = brokers.iterator().next();
            ConfigResource resource = new ConfigResource(ConfigResource.Type.BROKER,
                    String.valueOf(firstBroker.id()));
            Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);

            System.out.println();
            System.out.printf("  %-45s %-15s %-20s%n", "설정 키", "값", "소스");
            System.out.println("  " + "-".repeat(82));

            for (String key : targetConfigs) {
                ConfigEntry entry = config.get(key);
                String value  = entry != null ? entry.value() : "(없음)";
                String source = entry != null ? entry.source().toString() : "-";
                System.out.printf("  %-45s %-15s %-20s%n", key, value, source);
            }

            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - auto.leader.rebalance.enable=true(기본): 자동 선호 리더 복원 활성화");
            System.out.println("  - leader.imbalance.check.interval.seconds=300: 5분마다 불균형 확인");
            System.out.println("  - leader.imbalance.per.broker.percentage=10: 10% 초과 불균형 시 재조정");
            System.out.println("  - 테스트/개발 환경에선 false로 끄면 의도치 않은 리더 전환 방지");
            printSeparator();

            ConfigEntry autoRebalance = config.get("auto.leader.rebalance.enable");
            assertThat(autoRebalance).isNotNull();
            assertThat(autoRebalance.value()).isEqualTo("true");
        }
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private void printLeaderStatus(TopicDescription desc) {
        System.out.printf("    %-8s %-15s %-15s %-8s%n",
                "파티션", "선호 리더", "현재 리더", "일치?");
        System.out.println("    " + "-".repeat(50));
        for (TopicPartitionInfo p : desc.partitions()) {
            int preferred = p.replicas().get(0).id();
            int current   = p.leader().id();
            System.out.printf("    %-8d %-15s %-15s %-8s%n",
                    p.partition(),
                    "broker-" + preferred,
                    "broker-" + current,
                    preferred == current ? "✓" : "✗");
        }
    }
}
