package com.study.kafka.lab;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 08 — 복제 팩터와 ISR 상태 확인 (7장)
 *
 * 검증 명제: "클러스터가 정상일 때 ISR 크기는 항상 RF와 같은가?"
 *
 * Q1. RF별 ISR 크기: RF=1·2·3 토픽에서 ISR 크기가 RF와 일치하는가?
 * Q2. 레플리카 분산: RF=3일 때 3개의 서로 다른 브로커에 분산되는가?
 * Q3. 아웃오브싱크 없음: 정상 클러스터에서 out-of-sync 레플리카가 존재하는가?
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab08ReplicationFactorTest --info
 */
@Tag("lab")
@DisplayName("Lab 08 — 복제 팩터와 ISR 상태 확인")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab08ReplicationFactorTest {

    private static final String TOPIC_RF1 = "lab08-rf1";
    private static final String TOPIC_RF2 = "lab08-rf2";
    private static final String TOPIC_RF3 = "lab08-rf3";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 08: 복제 팩터와 ISR 상태 확인");
        System.out.println("  3-broker KRaft 클러스터 / RF=1,2,3 토픽 비교");
        System.out.println("=".repeat(62));
        createTopic(TOPIC_RF1, 3, (short) 1);
        createTopic(TOPIC_RF2, 3, (short) 2);
        createTopic(TOPIC_RF3, 3, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_RF1);
        deleteTopic(TOPIC_RF2);
        deleteTopic(TOPIC_RF3);
    }

    /**
     * Q1. RF별 ISR 크기 — ISR 크기는 항상 RF와 같은가?
     *
     * 클러스터가 완전히 정상(all brokers up, 복제 지연 없음)이면
     * ISR에는 리더 + 모든 팔로워가 포함된다. 따라서 ISR 크기 == RF.
     *
     * 레플리카가 ISR에서 제외되는 조건:
     *  - 브로커가 오프라인
     *  - 팔로워가 replica.lag.time.max.ms 안에 리더를 따라잡지 못함
     *  - ZooKeeper/KRaft 세션 만료
     */
    @Test
    @Order(1)
    @DisplayName("Q1: 정상 클러스터에서 ISR 크기는 RF와 같다")
    void isr_size_equals_replication_factor() throws Exception {
        System.out.println("\n  Q1: RF별 ISR 크기 확인");
        System.out.println("  → AdminClient.describeTopics()로 각 파티션의 replicas/ISR 조회");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            Map<String, Integer> rfByTopic = Map.of(
                    TOPIC_RF1, 1,
                    TOPIC_RF2, 2,
                    TOPIC_RF3, 3
            );

            Map<String, TopicDescription> descriptions = admin
                    .describeTopics(List.of(TOPIC_RF1, TOPIC_RF2, TOPIC_RF3))
                    .allTopicNames().get();

            System.out.println();
            System.out.printf("  %-20s %-5s %-5s %-5s %-6s %-6s%n",
                    "토픽", "파티션", "RF", "ISR", "ISR=RF?", "리더");
            System.out.println("  " + "-".repeat(52));

            for (Map.Entry<String, Integer> entry : rfByTopic.entrySet()) {
                String topicName = entry.getKey();
                int expectedRf = entry.getValue();
                TopicDescription desc = descriptions.get(topicName);

                for (TopicPartitionInfo p : desc.partitions()) {
                    int replicaCount = p.replicas().size();
                    int isrCount = p.isr().size();
                    boolean match = (isrCount == replicaCount);
                    System.out.printf("  %-20s %-5d %-5d %-5d %-6s broker-%d%n",
                            topicName, p.partition(), replicaCount, isrCount,
                            match ? "✓" : "✗", p.leader().id());

                    assertThat(replicaCount)
                            .as("토픽 %s 파티션 %d의 RF", topicName, p.partition())
                            .isEqualTo(expectedRf);
                    assertThat(isrCount)
                            .as("토픽 %s 파티션 %d의 ISR 크기가 RF와 같아야 한다", topicName, p.partition())
                            .isEqualTo(expectedRf);
                }
            }

            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - 모든 브로커가 정상이면 ISR == replicas (out-of-sync 없음)");
            System.out.println("  - 브로커 장애 시 해당 브로커의 레플리카가 ISR에서 제외된다");
            System.out.println("  - ISR 축소 → acks=all 지연 증가 or NOT_ENOUGH_REPLICAS 오류 가능");
            printSeparator();
        }
    }

    /**
     * Q2. 레플리카 분산 — RF=3일 때 레플리카가 3개의 서로 다른 브로커에 분산되는가?
     *
     * 카프카는 토픽 생성 시 레플리카를 여러 브로커에 분산한다.
     * RF=3이면 각 파티션의 3개 레플리카는 서로 다른 브로커에 배치된다.
     * 이를 통해 브로커 1대 장애 시에도 나머지 2개 레플리카로 서비스가 유지된다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: RF=3 토픽은 레플리카가 3개의 서로 다른 브로커에 분산된다")
    void replicas_are_distributed_across_brokers() throws Exception {
        System.out.println("\n  Q2: RF=3 레플리카 분산 확인");
        System.out.println("  → 각 파티션의 레플리카가 다른 브로커 ID에 배치되는지 확인");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            TopicDescription desc = admin.describeTopics(List.of(TOPIC_RF3))
                    .allTopicNames().get().get(TOPIC_RF3);

            System.out.println();
            System.out.printf("  %-8s %-30s %-8s%n", "파티션", "레플리카 브로커 목록", "분산?");
            System.out.println("  " + "-".repeat(50));

            Set<Integer> allBrokerIds = new HashSet<>();

            for (TopicPartitionInfo p : desc.partitions()) {
                List<Integer> brokerIds = p.replicas().stream()
                        .map(node -> node.id())
                        .collect(Collectors.toList());

                Set<Integer> uniqueBrokers = new HashSet<>(brokerIds);
                allBrokerIds.addAll(uniqueBrokers);

                String brokerList = brokerIds.stream()
                        .map(id -> "broker-" + id)
                        .collect(Collectors.joining(", "));

                boolean distributed = uniqueBrokers.size() == brokerIds.size();
                System.out.printf("  %-8d %-30s %-8s%n",
                        p.partition(), brokerList, distributed ? "✓" : "✗");

                assertThat(uniqueBrokers)
                        .as("파티션 %d의 레플리카는 서로 다른 브로커에 있어야 한다", p.partition())
                        .hasSize(3);
            }

            System.out.println();
            System.out.printf("  전체 파티션에서 사용된 브로커 ID: %s%n",
                    allBrokerIds.stream().sorted().map(id -> "broker-" + id)
                            .collect(Collectors.joining(", ")));
            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - RF=3이면 각 파티션의 3개 레플리카가 3개의 다른 브로커에 분산된다");
            System.out.println("  - 브로커 1대 장애 → 남은 2개 레플리카로 계속 서비스 가능 (RF=3의 장점)");
            System.out.println("  - rack.aware 설정 시 서로 다른 랙/AZ에도 분산할 수 있다");
            printSeparator();
        }
    }

    /**
     * Q3. 아웃오브싱크 레플리카 없음 — 정상 클러스터에서 out-of-sync가 존재하는가?
     *
     * out-of-sync replica = replicas에는 있지만 ISR에는 없는 레플리카
     * 정상 클러스터에서는 0이어야 한다.
     *
     * out-of-sync 레플리카의 의미:
     *  - 해당 레플리카는 리더 선출 후보에서 제외 (unclean election 비활성화 시)
     *  - acks=all 완료에 영향 없음 (ISR에 없으므로 대기 대상 아님)
     *  - 다시 따라잡으면 ISR에 자동 복귀
     */
    @Test
    @Order(3)
    @DisplayName("Q3: 정상 클러스터에서 out-of-sync 레플리카는 0개이다")
    void no_out_of_sync_replicas_in_healthy_cluster() throws Exception {
        System.out.println("\n  Q3: 아웃오브싱크(Out-of-Sync) 레플리카 확인");
        System.out.println("  → replicas에 있지만 ISR에 없는 레플리카 수 계산");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            Map<String, TopicDescription> descriptions = admin
                    .describeTopics(List.of(TOPIC_RF1, TOPIC_RF2, TOPIC_RF3))
                    .allTopicNames().get();

            System.out.println();
            System.out.printf("  %-20s %-8s %-10s %-12s %-12s%n",
                    "토픽", "파티션", "레플리카수", "ISR수", "OOS수");
            System.out.println("  " + "-".repeat(64));

            int totalOos = 0;
            for (Map.Entry<String, TopicDescription> entry : descriptions.entrySet()) {
                String topicName = entry.getKey();
                for (TopicPartitionInfo p : entry.getValue().partitions()) {
                    Set<Integer> replicaIds = p.replicas().stream()
                            .map(n -> n.id()).collect(Collectors.toSet());
                    Set<Integer> isrIds = p.isr().stream()
                            .map(n -> n.id()).collect(Collectors.toSet());

                    Set<Integer> oosReplicas = new HashSet<>(replicaIds);
                    oosReplicas.removeAll(isrIds);
                    int oosCount = oosReplicas.size();
                    totalOos += oosCount;

                    System.out.printf("  %-20s %-8d %-10d %-12d %-12s%n",
                            topicName, p.partition(),
                            replicaIds.size(), isrIds.size(),
                            oosCount == 0 ? "0 ✓" : oosCount + " ✗");

                    assertThat(oosCount)
                            .as("토픽 %s 파티션 %d의 out-of-sync 레플리카가 없어야 한다",
                                    topicName, p.partition())
                            .isZero();
                }
            }

            System.out.println();
            System.out.printf("  전체 out-of-sync 레플리카 수: %d%n", totalOos);
            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - out-of-sync = replicas - ISR (리더에서 뒤처진 팔로워)");
            System.out.println("  - 정상 클러스터에서는 항상 0이어야 한다");
            System.out.println("  - out-of-sync 발생 시 해당 레플리카는 리더 선출 후보에서 제외");
            System.out.println("  - unclean.leader.election.enable=true이면 out-of-sync도 리더 선출 가능");
            printSeparator();
        }
    }
}
