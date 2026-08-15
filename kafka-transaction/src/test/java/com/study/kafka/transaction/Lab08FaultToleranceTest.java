package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TransactionDescription;
import org.apache.kafka.clients.admin.TransactionListing;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.NotEnoughReplicasAfterAppendException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 08 — 트랜잭션의 장애 내성
 *
 * 검증 명제: "트랜잭션은 코디네이터와 min ISR에 의존한다"
 *
 * 지금까지의 Lab은 클러스터가 멀쩡하다는 전제 아래 트랜잭션의 의미론(원자성·격리·펜싱)을 봤다.
 * 이 Lab은 그 전제를 흔든다. 트랜잭션은 마법이 아니라 두 개의 복제 장치 위에 서 있다.
 *
 *   1) 데이터 파티션의 min.insync.replicas — 여기에 못 미치면 send 자체가 거부된다.
 *   2) __transaction_state의 복제(RF=3 / min ISR=2) — 코디네이터 상태 자체가 복제되어 있어서
 *      코디네이터를 맡던 브로커가 죽어도 다른 브로커가 인계받는다.
 *
 * 이 둘이 각각 "안전(부분 반영 차단)"과 "가용(브로커 하나쯤 죽어도 계속 동작)"을 책임진다.
 * 안전과 가용은 맞바꾸는 관계다 — 아래 Q1은 안전 쪽으로 극단까지 민 경우고,
 * Q2·Q3은 RF=3 / min ISR=2라는 균형점에서 가용성이 실제로 확보되는지 본다.
 *
 * Q1. min ISR을 만족할 수 없는 토픽에서는 트랜잭션 send 자체가 실패하고, 부분 반영은 일어나지 않는다.
 *     브로커를 죽이지 않고 재현한다 — replication factor 3짜리 토픽에 min.insync.replicas=4를 건다.
 *     ISR이 아무리 건강해도(3) 4를 만족할 수 없으므로 produce는 영원히 거부된다.
 *     그 뒤 트랜잭션을 abort하고 read_committed에 아무것도 보이지 않음을 확인한다.
 * Q2. RF=3 / min ISR=2 토픽에서는 브로커 1대를 정지시켜도 트랜잭션이 계속 동작한다.
 *     kafka-3을 정지시킨 뒤 새 트랜잭션을 커밋하고 read_committed로 보이는지 확인한다.
 * Q3. __transaction_state가 RF=3으로 복제되므로 코디네이터를 맡은 브로커가 죽어도 다른 브로커가 인계한다.
 *     브로커 1대가 빠진 상태에서 AdminClient의 listTransactions()/describeTransactions() 조회가 되는지,
 *     그리고 신규 트랜잭션을 새로 열어 커밋할 수 있는지 확인한다.
 *     ※ 코디네이터를 정확히 특정해 "그 브로커만" 죽이는 것은 __transaction_state의 파티션 배치에
 *       따라 매번 달라져 불안정하다. 그래서 여기서는 그렇게 하지 않는다. 대신
 *       "브로커가 하나 빠져도 트랜잭션 기능 전체가 계속 동작한다"를 관측하고,
 *       그 근거로 __transaction_state의 RF/min ISR 설정을 함께 출력한다.
 *
 * ★★★ 경고 — 이 Lab은 로컬 Kafka 컨테이너를 일시 정지시켰다가 다시 시작한다. ★★★
 * 정지 대상은 kafka-3(호스트 포트 9095) 하나로 고정한다. 부트스트랩 기본 창구인 kafka-1(9092)은
 * 건드리지 않는다. 각 테스트는 try/finally로 감싸 어떤 경로로 실패하든 finally에서 반드시
 * 재시작하며, @AfterAll에서 한 번 더 브로커 3대 복구를 확인한다.
 * 같은 클러스터를 쓰는 다른 작업이 있다면 이 Lab을 돌리지 말 것.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab08*' --info
 */
@Tag("lab")
@DisplayName("Lab 08 — 트랜잭션의 장애 내성")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab08FaultToleranceTest {

    private static final String TOPIC_MIN_ISR     = "tx-lab08-minisr";
    private static final String TOPIC_FAILOVER    = "tx-lab08-failover";
    private static final String TOPIC_COORDINATOR = "tx-lab08-coordinator";

    private static final String TX_ID_MIN_ISR     = "tx-lab08-minisr-worker";
    private static final String TX_ID_FAILOVER    = "tx-lab08-failover-worker";
    private static final String TX_ID_COORDINATOR = "tx-lab08-coordinator-worker";

    /** 정지시킬 브로커. kafka-1(9092)은 부트스트랩·다른 실습의 기본 창구라 절대 건드리지 않는다. */
    private static final String TARGET_SERVICE = "kafka-3";

    /** compose 라벨로 조회한 실제 컨테이너 이름. 이름을 하드코딩하지 않는다. */
    private static String targetContainer;

    /** docker CLI를 쓸 수 있는지. @AfterAll의 복구 로직이 이 값을 보고 동작한다. */
    private static boolean dockerAvailable;

    /** RF=3 토픽에 일부러 걸어둘 만족 불가능한 min ISR */
    private static final String IMPOSSIBLE_MIN_ISR = "4";

    /** 브로커 정지/기동 후 상태 변화를 기다리는 최대 시간. 고정 sleep 대신 폴링에 쓴다. */
    private static final long CLUSTER_WAIT_MS = 60_000;

    /** 폴링 간격 */
    private static final long POLL_INTERVAL_MS = 2_000;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");

        dockerAvailable = isDockerAvailable();
        assumeTrue(dockerAvailable,
                "docker CLI를 실행할 수 없어 실습을 건너뜁니다. (이 Lab은 브로커를 정지/재시작해야 합니다)");

        targetContainer = findComposeContainer(TARGET_SERVICE);
        assumeTrue(targetContainer != null && !targetContainer.isBlank(),
                "compose 라벨(com.docker.compose.service=" + TARGET_SERVICE + ")로 컨테이너를 찾지 못해 "
                        + "실습을 건너뜁니다.");

        printHeader("Lab 08: 트랜잭션의 장애 내성",
                "트랜잭션은 코디네이터와 min ISR에 의존한다");

        System.out.println();
        System.out.println("!".repeat(62));
        System.out.println("  ※※※  주의  ※※※");
        System.out.println("  이 실습은 Kafka 브로커를 일시 정지시켰다가 복구합니다.");
        System.out.printf("  대상 : %s (컨테이너 %s, 호스트 포트 9095)%n", TARGET_SERVICE, targetContainer);
        System.out.println("  방식 : docker stop → 관측 → docker start → 브로커 3대 복구까지 폴링");
        System.out.println("  각 테스트는 try/finally로 감싸 실패해도 반드시 재시작하며,");
        System.out.println("  @AfterAll에서 브로커 3대 복구를 한 번 더 확인합니다.");
        System.out.println("  같은 클러스터를 쓰는 다른 작업이 있다면 지금 중단하세요.");
        System.out.println("!".repeat(62));
        System.out.println();

        // 시작 시점부터 3대가 아니면 Q2·Q3의 전제가 깨진다.
        int brokers = brokerCount();
        System.out.printf("  시작 시점 브로커 수: %d%n", brokers);
        assumeTrue(brokers == 3, "브로커 3대 클러스터가 아니어서 실습을 건너뜁니다. (현재 " + brokers + "대)");

        // Q1: RF=3인데 min ISR을 4로 — ISR이 완전해도(3) 절대 만족할 수 없는 토픽
        createTopicWithConfig(TOPIC_MIN_ISR, 1, (short) 3,
                Map.of("min.insync.replicas", IMPOSSIBLE_MIN_ISR));
        // Q2·Q3: 브로커 기본값(min ISR=2)을 그대로 쓰는 정상 토픽
        createTopic(TOPIC_FAILOVER, 1, (short) 3);
        createTopic(TOPIC_COORDINATOR, 1, (short) 3);
    }

    /**
     * 마지막 안전망.
     * 각 테스트의 finally가 이미 재시작을 시도하지만, 그 finally 자체가 예외로 넘어갔거나
     * 테스트가 중간에 강제 중단된 경우를 대비해 여기서 한 번 더 확인한다.
     * 브로커가 멈춰 있으면 시작시키고, 클러스터가 3대로 돌아올 때까지 폴링한다.
     */
    @AfterAll
    static void tearDown() {
        if (dockerAvailable && targetContainer != null) {
            System.out.println();
            System.out.println("  [정리] 클러스터 복구 상태를 최종 확인합니다.");
            try {
                if (!isContainerRunning(targetContainer)) {
                    System.out.printf("  → %s 가 멈춰 있습니다. 다시 시작합니다.%n", targetContainer);
                    startContainer(targetContainer);
                }
                int brokers = awaitBrokerCount(3, CLUSTER_WAIT_MS);
                if (brokers == 3) {
                    System.out.println("  → 브로커 3대 클러스터로 정상 복구되었습니다.");
                } else {
                    printRecoveryWarning(brokers);
                }
            } catch (Exception e) {
                System.out.println("  → 복구 확인 중 오류: " + e);
                printRecoveryWarning(-1);
            }
        }

        deleteTopic(TOPIC_MIN_ISR);
        deleteTopic(TOPIC_FAILOVER);
        deleteTopic(TOPIC_COORDINATOR);
    }

    @Test
    @Order(1)
    @DisplayName("Q1. min ISR을 만족할 수 없으면 트랜잭션 send가 거부되고, 부분 반영도 일어나지 않는다")
    void sendFailsWhenMinIsrCannotBeSatisfied() throws Exception {
        // 브로커를 죽이지 않고 "ISR 부족" 상황을 만드는 방법:
        // 레플리카가 3개뿐인 토픽에 min.insync.replicas=4를 걸면 된다.
        // 세 브로커가 모두 건강하고 ISR이 꽉 차 있어도(3 < 4) acks=all produce는 절대 통과할 수 없다.
        // 트랜잭션 Producer는 acks=all이 강제되므로 이 조건에 정면으로 걸린다.
        String minIsr = topicConfig(TOPIC_MIN_ISR, "min.insync.replicas");
        TopicDescription desc = describeTopic(TOPIC_MIN_ISR);
        int replicas = desc.partitions().get(0).replicas().size();
        int isr = desc.partitions().get(0).isr().size();

        System.out.printf("  %s: replicas=%d, ISR=%d, min.insync.replicas=%s → 구조적으로 만족 불가%n",
                TOPIC_MIN_ISR, replicas, isr, minIsr);

        KafkaProducer<String, String> producer = failFastProducer(TX_ID_MIN_ISR);
        Throwable sendError = null;
        Throwable abortError = null;

        try {
            producer.initTransactions();
            producer.beginTransaction();
            try {
                // get()으로 결과를 끝까지 확인한다. 콜백만 걸어두면 실패를 놓친다.
                producer.send(new ProducerRecord<>(TOPIC_MIN_ISR, "order-1", "min ISR을 못 채우는 곳으로 보낸다"))
                        .get();
            } catch (Throwable t) {
                sendError = t;
            }

            // send가 실패하면 트랜잭션 매니저는 abortable error 상태가 된다 → abort만 허용된다.
            // 참고: 이 파티션은 이미 트랜잭션에 등록(AddPartitionsToTxn)되어 있어서
            //       코디네이터가 중단 마커(control batch)도 이 파티션에 써야 하는데,
            //       마커 역시 acks=all로 append되므로 같은 min ISR 벽에 막힌다.
            //       그래서 abortTransaction()이 max.block.ms까지 기다리다 타임아웃날 수 있다.
            //       이건 검증 대상이 아니라 관찰 대상이므로 단정하지 않고 출력만 한다.
            try {
                producer.abortTransaction();
            } catch (Throwable t) {
                abortError = t;
            }
        } finally {
            closeQuietly(producer);
        }

        System.out.printf("  send 결과        : %s%n", describeChain(sendError));
        System.out.printf("  abort 결과       : %s%n", describeChain(abortError));

        // 안 보여야 하는 것을 검증하므로 기대 개수를 크게 잡아 타임아웃까지 기다린다
        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_MIN_ISR, "lab08-minisr-c-" + System.nanoTime(), 99, 4000);
        List<ConsumerRecord<String, String>> uncommitted =
                readUncommitted(TOPIC_MIN_ISR, "lab08-minisr-u-" + System.nanoTime(), 99, 4000);
        printRecords("read_committed", committed);
        printRecords("read_uncommitted", uncommitted);
        System.out.println("  → min ISR은 '느려지는' 장치가 아니라 '거부하는' 장치다. "
                + "쓸 수 없으면 아예 안 쓴다 — 그래서 절반만 반영되는 상태가 생기지 않는다");
        printSeparator();

        // 이 토픽은 중단 마커조차 쓸 수 없어 코디네이터가 마커 재시도를 계속 물고 있을 수 있다.
        // 뒤이을 Q2·Q3에 잡음이 섞이지 않도록 여기서 먼저 지운다(@AfterAll에서도 한 번 더 지운다).
        deleteTopic(TOPIC_MIN_ISR);

        assertThat(replicas)
                .as("실습 전제: 레플리카 수(%d)가 min.insync.replicas(%s)보다 작아야 한다", replicas, minIsr)
                .isLessThan(Integer.parseInt(IMPOSSIBLE_MIN_ISR));
        assertThat(sendError)
                .as("min ISR을 만족할 수 없는 파티션으로의 트랜잭션 send는 성공하면 안 된다")
                .isNotNull();
        assertThat(isMinIsrError(sendError))
                .as("NotEnoughReplicasException이 본래 원인이지만 이 예외는 retriable이라 "
                        + "delivery.timeout.ms까지 재시도된 뒤 TimeoutException(Expiring ...)으로 "
                        + "감싸져 나올 수 있다. 그래서 특정 타입을 콕 집지 않고 원인 체인 전체를 훑어 "
                        + "판정한다: %s", describeChain(sendError))
                .isTrue();
        assertThat(committed)
                .as("결말이 나지 않은(그리고 애초에 기록되지도 못한) 트랜잭션의 메시지는 "
                        + "read_committed 소비자에게 절대 보이지 않는다")
                .isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("Q2. RF=3 / min ISR=2 토픽은 브로커 1대가 죽어도 트랜잭션이 계속 커밋된다")
    void transactionSurvivesSingleBrokerDown() throws Exception {
        int brokersWhileDown = -1;
        int isrWhileDown = -1;
        Throwable txError = null;
        List<ConsumerRecord<String, String>> committed = List.of();

        System.out.printf("%n  [1/4] %s 정지 → 브로커 2대 상태를 만든다%n", TARGET_SERVICE);
        stopContainer(targetContainer);

        try {
            // 정지 직후 곧바로 클러스터 메타데이터에 반영되지는 않는다.
            // 컨트롤러가 브로커를 fence하고 리더를 재선출할 때까지 폴링으로 기다린다.
            System.out.print("  [2/4] 클러스터가 2대로 줄어들 때까지 대기 ");
            brokersWhileDown = awaitBrokerCount(2, CLUSTER_WAIT_MS);

            System.out.print("  [3/4] " + TOPIC_FAILOVER + " 파티션의 리더 재선출·ISR 회복 대기 ");
            isrWhileDown = awaitLeaderWithIsrAtLeast(TOPIC_FAILOVER, 0, 2, CLUSTER_WAIT_MS);

            System.out.println("  [4/4] 브로커 2대 상태에서 새 트랜잭션을 커밋한다");
            KafkaProducer<String, String> producer = degradedClusterProducer(TX_ID_FAILOVER);
            try {
                producer.initTransactions();
                producer.beginTransaction();
                producer.send(new ProducerRecord<>(TOPIC_FAILOVER, "order-1", "브로커 1대가 없어도 커밋된다"));
                producer.send(new ProducerRecord<>(TOPIC_FAILOVER, "order-2", "min ISR=2는 여전히 만족된다"));
                producer.commitTransaction();
            } catch (Throwable t) {
                txError = t;
            } finally {
                closeQuietly(producer);
            }

            committed = readCommitted(TOPIC_FAILOVER, "lab08-failover-c-" + System.nanoTime(), 2, 10_000);
        } finally {
            // 어떤 경로로 빠져나가든(단정 실패·예외·에러) 여기를 반드시 지난다.
            restoreCluster();
        }

        System.out.printf("  정지 중 브로커 수 : %d%n", brokersWhileDown);
        System.out.printf("  정지 중 ISR 크기  : %d (min.insync.replicas=2)%n", isrWhileDown);
        System.out.printf("  트랜잭션 결과     : %s%n",
                txError == null ? "커밋 성공" : describeChain(txError));
        printRecords("read_committed", committed);
        System.out.println("  → RF=3 / min ISR=2는 '1대 장애를 견딘다'는 뜻이다. "
                + "레플리카 3개 중 2개만 살아 있어도 acks=all이 성립하기 때문이다");
        printSeparator();

        assertThat(brokersWhileDown)
                .as("%s를 정지시켰으므로 클러스터는 2대로 보여야 한다 (%dms 안에 반영되지 않았다면 "
                        + "컨트롤러의 브로커 fencing 설정을 확인할 것)", TARGET_SERVICE, CLUSTER_WAIT_MS)
                .isEqualTo(2);
        assertThat(isrWhileDown)
                .as("레플리카 3개 중 1개가 빠졌으므로 ISR은 2로 줄어들지만 min.insync.replicas=2는 여전히 만족한다")
                .isGreaterThanOrEqualTo(2);
        assertThat(txError)
                .as("min ISR을 만족하는 한 브로커 1대 장애는 트랜잭션을 막지 못한다: %s", describeChain(txError))
                .isNull();
        assertThat(committed)
                .as("브로커 2대 상태에서 커밋한 메시지는 read_committed 소비자에게 정상적으로 보인다")
                .hasSize(2);
    }

    @Test
    @Order(3)
    @DisplayName("Q3. __transaction_state가 RF=3이라 브로커 1대가 빠져도 트랜잭션 조회·신규 개시가 된다")
    void transactionCoordinationSurvivesSingleBrokerDown() throws Exception {
        // 먼저 코디네이터가 왜 살아남는지 그 근거부터 확인한다.
        // 트랜잭션 상태(어떤 txId가 어느 단계인지, epoch가 몇인지)는 브로커의 메모리에만 있는 게 아니라
        // __transaction_state 내부 토픽에 로그로 남는다. 이게 RF=3으로 복제되어 있으므로
        // 코디네이터를 맡던 브로커가 죽으면 그 파티션의 새 리더가 로그를 재생해 상태를 그대로 이어받는다.
        TopicDescription stateTopic = describeTopic(TX_STATE_TOPIC);
        int stateRf = stateTopic.partitions().get(0).replicas().size();
        String stateMinIsr = topicConfig(TX_STATE_TOPIC, "min.insync.replicas");

        System.out.printf("%n  %s: partitions=%d, RF=%d, min.insync.replicas=%s%n",
                TX_STATE_TOPIC, stateTopic.partitions().size(), stateRf, stateMinIsr);
        System.out.println("  → 코디네이터 상태 자체가 복제되어 있다는 뜻이다. 이것이 인계의 근거다.");

        int brokersWhileDown = -1;
        Collection<TransactionListing> listing = null;
        Throwable listError = null;
        TransactionDescription description = null;
        Throwable txError = null;
        List<ConsumerRecord<String, String>> committed = List.of();

        System.out.printf("%n  [1/4] %s 정지 → 브로커 2대 상태를 만든다%n", TARGET_SERVICE);
        stopContainer(targetContainer);

        try {
            System.out.print("  [2/4] 클러스터가 2대로 줄어들 때까지 대기 ");
            brokersWhileDown = awaitBrokerCount(2, CLUSTER_WAIT_MS);

            System.out.print("  [3/4] " + TOPIC_COORDINATOR + " 파티션의 리더 재선출·ISR 회복 대기 ");
            awaitLeaderWithIsrAtLeast(TOPIC_COORDINATOR, 0, 2, CLUSTER_WAIT_MS);

            System.out.println("  [4/4] 브로커 2대 상태에서 신규 트랜잭션 개시 + AdminClient 조회");
            KafkaProducer<String, String> producer = degradedClusterProducer(TX_ID_COORDINATOR);
            try {
                // 이 initTransactions()는 코디네이터를 새로 찾아 등록하는 과정이다.
                // 원래 코디네이터가 kafka-3이었다면 여기서 인계된 코디네이터를 만나게 된다.
                producer.initTransactions();
                producer.beginTransaction();
                producer.send(new ProducerRecord<>(TOPIC_COORDINATOR, "order-1",
                        "코디네이터가 인계돼도 새 트랜잭션은 열린다"));
                producer.commitTransaction();
            } catch (Throwable t) {
                txError = t;
            } finally {
                closeQuietly(producer);
            }

            // 조회 API도 브로커가 빠진 상태에서 동작해야 한다.
            try {
                listing = listTransactions();
            } catch (Throwable t) {
                listError = t;
            }
            description = describeTransaction(TX_ID_COORDINATOR);

            committed = readCommitted(TOPIC_COORDINATOR, "lab08-coord-c-" + System.nanoTime(), 1, 10_000);
        } finally {
            // Q2와 동일하게, 어떤 경로로 빠져나가든 반드시 복구한다.
            restoreCluster();
        }

        System.out.printf("  정지 중 브로커 수 : %d%n", brokersWhileDown);
        System.out.printf("  트랜잭션 결과     : %s%n",
                txError == null ? "커밋 성공" : describeChain(txError));
        System.out.printf("  listTransactions(): %s%n",
                listError != null ? "실패 — " + describeChain(listError)
                        : (listing == null ? "(null)" : listing.size() + "건 조회됨"));
        System.out.printf("  describeTransactions(%s): %s%n", TX_ID_COORDINATOR,
                description == null ? "(조회 실패)" : description.state().toString());
        printRecords("read_committed", committed);
        System.out.println("  → 코디네이터는 특정 브로커에 고정된 단일 장애점이 아니다. "
                + "__transaction_state 파티션의 리더가 곧 코디네이터이고, 리더는 옮겨갈 수 있다");
        printSeparator();

        assertThat(stateRf)
                .as("%s가 RF=3이어야 브로커 1대 장애를 견딘다 (KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=3)",
                        TX_STATE_TOPIC)
                .isEqualTo(3);
        assertThat(stateMinIsr)
                .as("%s의 min ISR=2여야 브로커 2대만으로도 코디네이터가 상태를 기록할 수 있다 "
                        + "(KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=2)", TX_STATE_TOPIC)
                .isEqualTo("2");
        assertThat(brokersWhileDown)
                .as("%s를 정지시켰으므로 클러스터는 2대로 보여야 한다", TARGET_SERVICE)
                .isEqualTo(2);
        assertThat(listError)
                .as("브로커가 하나 빠져도 살아 있는 브로커들로부터 트랜잭션 목록을 조회할 수 있어야 한다: %s",
                        describeChain(listError))
                .isNull();
        assertThat(listing)
                .as("listTransactions()가 결과를 돌려줘야 한다 (건수 자체는 다른 실습의 잔재에 따라 달라진다)")
                .isNotNull();
        assertThat(txError)
                .as("코디네이터가 인계된 뒤에도 신규 트랜잭션 개시·커밋이 가능해야 한다: %s", describeChain(txError))
                .isNull();
        assertThat(description)
                .as("커밋을 끝낸 트랜잭션의 상태를 브로커 2대 상태에서도 조회할 수 있어야 한다")
                .isNotNull();
        assertThat(committed)
                .as("브로커 2대 상태에서 커밋한 메시지는 read_committed 소비자에게 정상적으로 보인다")
                .hasSize(1);
    }

    // ── Producer 설정 ──────────────────────────────────────────────

    /**
     * Q1용 — 실패를 빨리 확정짓는 Producer.
     *
     * NotEnoughReplicasException은 retriable이라 기본값(delivery.timeout.ms=120000)이면
     * 2분을 재시도하다 실패한다. 실습 시간을 위해 8초로 줄인다.
     * (제약: delivery.timeout.ms >= linger.ms + request.timeout.ms)
     * max.block.ms도 함께 줄인다 — abortTransaction()이 중단 마커를 못 써서 매달릴 수 있기 때문이다.
     */
    private static KafkaProducer<String, String> failFastProducer(String transactionalId) {
        return transactionalProducer(transactionalId, Map.of(
                ProducerConfig.LINGER_MS_CONFIG, "0",
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000",
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "8000",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, "8000"));
    }

    /**
     * Q2·Q3용 — 브로커 1대가 빠진 상태를 견디는 Producer.
     *
     * 리더 재선출 직후에는 메타데이터 갱신과 코디네이터 재탐색이 겹쳐 한 박자 느릴 수 있다.
     * 기본값보다 여유를 두되, 무한정 매달리지는 않게 상한을 명시한다.
     */
    private static KafkaProducer<String, String> degradedClusterProducer(String transactionalId) {
        return transactionalProducer(transactionalId, Map.of(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, "30000",
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "15000",
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "40000"));
    }

    // ── 클러스터 복구 ──────────────────────────────────────────────

    /**
     * 정지시킨 브로커를 되살리고 클러스터가 3대로 돌아올 때까지 폴링한다.
     * 이 메서드 자체는 예외를 던지지 않는다 — finally 안에서 호출되므로 여기서 예외가 나면
     * 원래 테스트의 실패 원인을 덮어써버린다.
     */
    private static void restoreCluster() {
        try {
            System.out.printf("%n  [복구] %s 를 다시 시작합니다.%n", targetContainer);
            startContainer(targetContainer);
            System.out.print("  [복구] 브로커 3대로 돌아올 때까지 대기 ");
            int brokers = awaitBrokerCount(3, CLUSTER_WAIT_MS);
            if (brokers == 3) {
                System.out.println("  [복구] 완료 — 브로커 3대 클러스터로 돌아왔습니다.");
            } else {
                printRecoveryWarning(brokers);
            }
        } catch (Exception e) {
            System.out.println("  [복구] 중 오류: " + e);
            printRecoveryWarning(-1);
        }
    }

    /** 사람이 읽고 바로 조치할 수 있는 형태로 경고한다. */
    private static void printRecoveryWarning(int observed) {
        System.out.println();
        System.out.println("!".repeat(62));
        System.out.println("  ※※※  복구 실패 — 수동 확인이 필요합니다  ※※※");
        System.out.printf("  기대: 브로커 3대 / 관측: %s%n", observed < 0 ? "확인 불가" : observed + "대");
        System.out.printf("  1) docker start %s%n", targetContainer);
        System.out.println("  2) docker compose ps   (kafka-1/2/3 이 모두 Up 인지 확인)");
        System.out.println("  3) 그래도 안 되면: docker compose up -d");
        System.out.println("  이 상태를 두면 다른 Lab이 RF=3 전제를 만족하지 못해 실패합니다.");
        System.out.println("!".repeat(62));
        System.out.println();
    }

    // ── 폴링 헬퍼 ──────────────────────────────────────────────────

    /**
     * 클러스터의 브로커 수가 expected가 될 때까지 폴링한다.
     *
     * 고정 sleep을 쓰지 않는 이유: docker stop 이후 컨트롤러가 브로커를 fence하고
     * 리더를 재선출하는 데 걸리는 시간, docker start 이후 브로커가 로그를 재생하고
     * 등록을 마치는 시간이 모두 환경마다 다르다. 상수로 못 박으면 환경에 따라 깨진다.
     * 진행 상황을 점(.)으로 찍어 테스트가 멈춘 게 아님을 알린다.
     */
    private static int awaitBrokerCount(int expected, long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        int count = -1;

        while (System.currentTimeMillis() < deadline) {
            count = safeBrokerCount();
            if (count == expected) {
                break;
            }
            System.out.print(".");
            System.out.flush();
            if (!sleepQuietly(POLL_INTERVAL_MS)) {
                break;
            }
        }

        double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%n  → %.1f초 후 브로커 수 = %s (기대 %d)%n",
                elapsedSec, count < 0 ? "조회 실패" : String.valueOf(count), expected);
        return count;
    }

    /**
     * 파티션에 리더가 서고 ISR이 minIsr 이상이 될 때까지 폴링한다.
     * 브로커가 빠진 직후에는 리더가 잠시 없을 수 있어서(-1) produce가 실패한다 —
     * 그 과도기를 지나쳤는지 확인하는 용도다. 관측된 ISR 크기를 반환한다.
     */
    private static int awaitLeaderWithIsrAtLeast(String topic, int partition, int minIsr, long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        int isr = -1;
        int leaderId = -1;

        while (System.currentTimeMillis() < deadline) {
            try {
                TopicPartitionInfo info = describeTopic(topic).partitions().get(partition);
                Node leader = info.leader();
                leaderId = (leader == null) ? -1 : leader.id();
                isr = info.isr().size();
                if (leaderId >= 0 && isr >= minIsr) {
                    break;
                }
            } catch (Exception ignored) {
                // 메타데이터 갱신 중일 수 있다. 다음 회차에 다시 본다.
            }
            System.out.print(".");
            System.out.flush();
            if (!sleepQuietly(POLL_INTERVAL_MS)) {
                break;
            }
        }

        double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("%n  → %.1f초 후 %s-%d: leader=%s, ISR=%d (필요 %d)%n",
                elapsedSec, topic, partition,
                leaderId < 0 ? "없음" : String.valueOf(leaderId), isr, minIsr);
        return isr;
    }

    /** 브로커 수 조회는 브로커가 빠진 직후 실패할 수 있다. 폴링 루프를 깨지 않도록 -1로 흘린다. */
    private static int safeBrokerCount() {
        try {
            return brokerCount();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 인터럽트되면 false를 반환해 폴링 루프를 빠져나가게 한다. */
    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── docker 제어 ────────────────────────────────────────────────
    //
    // 컨테이너 이름은 환경(프로젝트 디렉터리 이름·compose 버전)에 따라 달라지므로 하드코딩하지 않는다.
    // Lab07PhysicalStorageTest와 같은 방식으로 compose가 붙여주는 라벨
    // (com.docker.compose.service=kafka-3)로 조회한 뒤, 그 이름으로 stop/start 한다.
    // docker compose stop을 쓰지 않는 이유: compose 하위 명령은 실행 디렉터리에 있는
    // docker-compose.yml을 찾아야 하는데, 테스트의 작업 디렉터리는 Gradle 모듈 디렉터리라
    // 프로젝트 루트가 아니다. 라벨 조회 + docker stop/start 조합이 위치에 영향받지 않는다.

    /** docker CLI를 실행할 수 있는지 확인한다. 데몬이 꺼져 있어도 exit code가 0이 아니다. */
    private static boolean isDockerAvailable() {
        DockerResult result = runDocker(15, "ps", "--format", "{{.Names}}");
        if (!result.ok()) {
            System.out.println("  docker CLI 확인 실패: " + result.output().trim());
        }
        return result.ok();
    }

    /** compose 서비스 라벨로 컨테이너 이름을 찾는다. 정지된 컨테이너도 찾도록 -a를 쓴다. */
    private static String findComposeContainer(String service) {
        DockerResult result = runDocker(15, "ps", "-a",
                "--filter", "label=com.docker.compose.service=" + service,
                "--format", "{{.Names}}");
        if (!result.ok()) {
            return null;
        }
        return Arrays.stream(result.output().split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private static boolean isContainerRunning(String container) {
        DockerResult result = runDocker(15, "ps",
                "--filter", "name=" + container,
                "--filter", "status=running",
                "--format", "{{.Names}}");
        if (!result.ok()) {
            return false;
        }
        return Arrays.stream(result.output().split("\\r?\\n"))
                .map(String::trim)
                .anyMatch(container::equals);
    }

    /**
     * 컨테이너를 정지시킨다. docker rm이나 볼륨 삭제는 절대 하지 않는다 — 정지/시작만 한다.
     * SIGTERM 후 Kafka가 controlled shutdown을 수행하므로 최대 수십 초 걸릴 수 있다.
     */
    private static void stopContainer(String container) {
        DockerResult result = runDocker(90, "stop", container);
        System.out.printf("  docker stop %s → %s%n", container,
                result.ok() ? "정지됨" : "실패: " + result.output().trim());
    }

    private static void startContainer(String container) {
        DockerResult result = runDocker(90, "start", container);
        System.out.printf("  docker start %s → %s%n", container,
                result.ok() ? "기동 요청 완료" : "실패: " + result.output().trim());
    }

    private record DockerResult(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    /** docker 명령을 실행하고 (종료 코드, stdout+stderr)를 반환한다. */
    private static DockerResult runDocker(long timeoutSec, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // 스트림을 먼저 다 읽어야 파이프 버퍼가 차서 프로세스가 멈추는 일이 없다.
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new DockerResult(-1, output + "\n[docker 명령이 " + timeoutSec + "초 안에 끝나지 않음]");
            }
            return new DockerResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DockerResult(-1, "[docker 실행 중 인터럽트]");
        } catch (Exception e) {
            return new DockerResult(-1, "[docker 실행 실패: " + e.getMessage() + "]");
        }
    }

    // ── 예외 판정 / 정리 (Lab 04와 같은 방식) ──────────────────────

    /**
     * min ISR을 만족하지 못할 때 나올 수 있는 예외 후보들.
     *
     * 브로커가 돌려주는 본래 오류는 NOT_ENOUGH_REPLICAS다. 그런데 이 오류는 retriable이라
     * Producer가 delivery.timeout.ms까지 재시도하고, 그 시한이 다하면 배치가
     * TimeoutException("Expiring N record(s) ...")으로 실패 처리된다.
     * 즉 실제로 손에 들어오는 예외는 클라이언트 버전과 타이밍에 따라 둘 중 하나다.
     * 그래서 특정 타입을 단정하지 않고 후보 집합으로 판정한다 —
     * 어느 쪽이든 "send가 거부되어 데이터가 반영되지 않았다"는 명제는 동일하게 성립한다.
     */
    private static boolean isMinIsrError(Throwable error) {
        return causedByAny(error,
                NotEnoughReplicasException.class,
                NotEnoughReplicasAfterAppendException.class,
                TimeoutException.class);
    }

    /** 예외 원인 체인에 후보 타입 중 하나라도 있는지 확인한다. */
    private static boolean causedByAny(Throwable error, Class<?>... candidates) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            for (Class<?> candidate : candidates) {
                if (candidate.isInstance(cause)) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break; // 자기 자신을 원인으로 갖는 예외 방어
            }
        }
        return false;
    }

    /** 예외 원인 체인을 "A -> B -> C" 형태의 한 줄로 만든다. */
    private static String describeChain(Throwable error) {
        if (error == null) {
            return "(예외 없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(cause.getClass().getSimpleName());
            if (cause.getCause() == cause) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Producer를 조용히 닫는다.
     * 실패했거나 abort에 실패한 Producer는 close() 과정에서 예외를 던질 수 있는데,
     * 그건 검증 대상이 아니라 뒷정리다. close(Duration.ZERO)로 대기 없이 강제 종료한다.
     */
    private static void closeQuietly(KafkaProducer<String, String> producer) {
        if (producer == null) {
            return;
        }
        try {
            producer.close(Duration.ZERO);
        } catch (Exception ignored) {
        }
    }
}
