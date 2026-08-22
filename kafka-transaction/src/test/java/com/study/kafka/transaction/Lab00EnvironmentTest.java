package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TransactionDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 00 — 트랜잭션 실습 환경 점검
 *
 * 검증 명제: "이 클러스터에서 Kafka 트랜잭션 실습을 할 수 있다"
 *
 * Q1. 브로커 3대가 떠 있는가? (트랜잭션 코디네이터는 RF=3 / min ISR=2 전제)
 * Q2. 트랜잭션 커밋/중단이 read_committed 소비자에게 다르게 보이는가?
 * Q3. 진행 중 트랜잭션이 LSO(Last Stable Offset)를 붙잡는가?
 * Q4. __transaction_state 내부 토픽이 기대한 설정으로 만들어졌는가?
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab00*' --info
 */
@Tag("lab")
@DisplayName("Lab 00 — 트랜잭션 실습 환경 점검")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab00EnvironmentTest {

    private static final String TOPIC_COMMIT = "tx-lab00-commit";
    private static final String TOPIC_ABORT  = "tx-lab00-abort";
    private static final String TOPIC_LSO    = "tx-lab00-lso";

    private static final String TX_ID_COMMIT = "tx-lab00-committer";
    private static final String TX_ID_ABORT  = "tx-lab00-aborter";
    private static final String TX_ID_LSO    = "tx-lab00-lso-holder";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 00: 트랜잭션 실습 환경 점검",
                "커밋/중단이 소비자에게 어떻게 다르게 보이는가?");
        createTopic(TOPIC_COMMIT, 1, (short) 3);
        createTopic(TOPIC_ABORT, 1, (short) 3);
        createTopic(TOPIC_LSO, 1, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_COMMIT);
        deleteTopic(TOPIC_ABORT);
        deleteTopic(TOPIC_LSO);
    }

    @Test
    @Order(1)
    @DisplayName("Q1. 브로커 3대 클러스터가 떠 있다")
    void clusterHasThreeBrokers() throws Exception {
        int brokers = brokerCount();
        System.out.printf("  브로커 수: %d%n", brokers);
        printSeparator();

        assertThat(brokers)
                .as("트랜잭션 코디네이터가 RF=3 / min ISR=2로 설정되어 있으므로 3대가 필요하다")
                .isEqualTo(3);
    }

    @Test
    @Order(2)
    @DisplayName("Q2-1. commitTransaction()한 메시지는 read_committed에도 보인다")
    void committedMessagesAreVisible() throws Exception {
        try (KafkaProducer<String, String> producer = transactionalProducer(TX_ID_COMMIT)) {
            producer.initTransactions();
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(TOPIC_COMMIT, "order-1", "created"));
            producer.send(new ProducerRecord<>(TOPIC_COMMIT, "order-2", "created"));
            producer.commitTransaction();
        }

        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_COMMIT, "lab00-committed-" + System.nanoTime(), 2, 5000);
        printRecords("read_committed", committed);
        printSeparator();

        assertThat(committed).hasSize(2);
    }

    @Test
    @Order(3)
    @DisplayName("Q2-2. abortTransaction()한 메시지는 read_committed에 안 보이고 read_uncommitted에는 보인다")
    void abortedMessagesAreFilteredOnlyForReadCommitted() throws Exception {
        try (KafkaProducer<String, String> producer = transactionalProducer(TX_ID_ABORT)) {
            producer.initTransactions();
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(TOPIC_ABORT, "order-9", "will-be-aborted"));
            producer.flush(); // 브로커 로그에는 이미 기록된 상태로 만든다
            producer.abortTransaction();
        }

        // 안 보여야 하는 것을 검증하므로 기대 개수를 크게 잡아 타임아웃까지 기다린다
        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_ABORT, "lab00-abort-c-" + System.nanoTime(), 99, 4000);
        List<ConsumerRecord<String, String>> uncommitted =
                readUncommitted(TOPIC_ABORT, "lab00-abort-u-" + System.nanoTime(), 1, 4000);

        printRecords("read_committed", committed);
        printRecords("read_uncommitted", uncommitted);
        printSeparator();

        assertThat(committed)
                .as("중단된 트랜잭션의 메시지는 read_committed 소비자에게 걸러진다")
                .isEmpty();
        assertThat(uncommitted)
                .as("메시지는 로그에 물리적으로 남아 있다 — 걸러내는 주체는 소비자다")
                .hasSize(1);
    }

    @Test
    @Order(4)
    @DisplayName("Q3. 진행 중인 트랜잭션은 LSO를 붙잡고, 커밋하면 풀린다")
    void ongoingTransactionHoldsLastStableOffset() throws Exception {
        try (KafkaProducer<String, String> producer = transactionalProducer(TX_ID_LSO)) {
            producer.initTransactions();
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(TOPIC_LSO, "pending-1", "in-flight"));
            producer.send(new ProducerRecord<>(TOPIC_LSO, "pending-2", "in-flight"));
            producer.flush();

            long lsoOngoing = lastStableOffset(TOPIC_LSO, 0);
            long hwOngoing = highWatermark(TOPIC_LSO, 0);
            System.out.printf("  트랜잭션 진행 중 : LSO=%d, HW=%d%n", lsoOngoing, hwOngoing);

            TransactionDescription desc = describeTransaction(TX_ID_LSO);
            System.out.printf("  트랜잭션 상태     : %s%n",
                    desc == null ? "(조회 실패)" : desc.state().toString());

            assertThat(lsoOngoing)
                    .as("진행 중 트랜잭션의 첫 메시지 위치에서 LSO가 멈춘다")
                    .isEqualTo(0);
            assertThat(hwOngoing)
                    .as("메시지 자체는 이미 복제되어 HW는 앞으로 나간다")
                    .isEqualTo(2);

            producer.commitTransaction();
        }

        long lsoCommitted = lastStableOffset(TOPIC_LSO, 0);
        long hwCommitted = highWatermark(TOPIC_LSO, 0);
        System.out.printf("  커밋 후          : LSO=%d, HW=%d%n", lsoCommitted, hwCommitted);
        printSeparator();

        assertThat(lsoCommitted)
                .as("커밋 마커(control batch)가 오프셋 1칸을 차지하므로 메시지 2개 + 마커 1개 = 3")
                .isEqualTo(3);
        assertThat(hwCommitted).isEqualTo(lsoCommitted);
    }

    @Test
    @Order(5)
    @DisplayName("Q4. __transaction_state 내부 토픽이 RF=3 / min ISR=2로 준비되어 있다")
    void transactionStateTopicIsConfigured() throws Exception {
        TopicDescription desc = describeTopic(TX_STATE_TOPIC);
        int replicationFactor = desc.partitions().get(0).replicas().size();
        String minIsr = topicConfig(TX_STATE_TOPIC, "min.insync.replicas");

        System.out.printf("  %s: partitions=%d, RF=%d, min.insync.replicas=%s%n",
                TX_STATE_TOPIC, desc.partitions().size(), replicationFactor, minIsr);
        printSeparator();

        assertThat(replicationFactor)
                .as("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=3")
                .isEqualTo(3);
        assertThat(minIsr)
                .as("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=2")
                .isEqualTo("2");
    }
}
