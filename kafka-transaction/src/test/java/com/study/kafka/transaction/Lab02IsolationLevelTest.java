package com.study.kafka.transaction;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 02 — isolation.level과 LSO 소비 지연
 *
 * 검증 명제: "긴 트랜잭션은 같은 파티션의 무관한 메시지까지 막는다 (head-of-line blocking)"
 *
 * read_committed 소비자는 HW(High Watermark)가 아니라 LSO(Last Stable Offset)까지만 읽는다.
 * LSO는 "아직 결말이 안 난 가장 오래된 트랜잭션의 시작 오프셋"이다.
 * 따라서 트랜잭션 하나가 열린 채로 방치되면, 그 뒤에 커밋을 끝낸 다른 트랜잭션의 메시지까지
 * 통째로 소비가 막힌다. 트랜잭션은 파티션 단위가 아니라 "오프셋 순서" 단위로 막는다.
 *
 * Q1. Producer A가 트랜잭션을 열어둔 채, Producer B가 같은 파티션에 쓰고 커밋한다.
 *     read_committed 소비자에게 B의 메시지가 보이는가? → 안 보인다. A를 끝내야 그제서야 보인다.
 * Q2. 같은 상황에서 read_uncommitted 소비자는 A/B 메시지를 즉시 본다. 두 레벨을 대조한다.
 * Q3. 커밋 마커(control batch)가 오프셋을 1칸씩 차지한다.
 *     "실제 메시지 수 < 끝 오프셋"이 되어, 모니터링에서 consumer lag이 0으로 안 떨어지는 것처럼 보인다.
 *
 * 파티션은 반드시 1개다. A와 B가 같은 파티션에 써야 head-of-line blocking이 재현된다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab02*' --info
 */
@Tag("lab")
@DisplayName("Lab 02 — isolation.level과 LSO")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab02IsolationLevelTest {

    private static final String TOPIC_BLOCKING = "tx-lab02-blocking";
    private static final String TOPIC_ISOLATION = "tx-lab02-isolation";
    private static final String TOPIC_MARKER = "tx-lab02-marker";

    private static final String TX_ID_SLOW_A = "tx-lab02-slow-a";
    private static final String TX_ID_FAST_B = "tx-lab02-fast-b";
    private static final String TX_ID_ISO_A = "tx-lab02-iso-a";
    private static final String TX_ID_ISO_B = "tx-lab02-iso-b";
    private static final String TX_ID_MARKER = "tx-lab02-marker-writer";

    /** Q3에서 반복할 트랜잭션 횟수 */
    private static final int TX_ROUNDS = 3;
    /** Q3에서 트랜잭션 1건당 보낼 메시지 수 */
    private static final int MSG_PER_TX = 2;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 02: isolation.level과 LSO 소비 지연",
                "열린 트랜잭션 하나가 같은 파티션의 무관한 메시지까지 막는다");
        // 파티션 1개 — A와 B가 같은 파티션에 써야 head-of-line blocking이 재현된다
        createTopic(TOPIC_BLOCKING, 1, (short) 3);
        createTopic(TOPIC_ISOLATION, 1, (short) 3);
        createTopic(TOPIC_MARKER, 1, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_BLOCKING);
        deleteTopic(TOPIC_ISOLATION);
        deleteTopic(TOPIC_MARKER);
    }

    @Test
    @Order(1)
    @DisplayName("Q1. 열린 트랜잭션 A가 뒤이어 커밋된 B의 메시지까지 막는다 (head-of-line blocking)")
    void ongoingTransactionBlocksLaterCommittedMessages() throws Exception {
        try (KafkaProducer<String, String> producerA = transactionalProducer(TX_ID_SLOW_A);
             KafkaProducer<String, String> producerB = transactionalProducer(TX_ID_FAST_B)) {

            // A: 트랜잭션을 열고 보내기만 한다. 커밋하지 않고 방치한다. → offset 0, 1
            producerA.initTransactions();
            producerA.beginTransaction();
            producerA.send(new ProducerRecord<>(TOPIC_BLOCKING, "slow-1", "A가 아직 커밋 안 함"));
            producerA.send(new ProducerRecord<>(TOPIC_BLOCKING, "slow-2", "A가 아직 커밋 안 함"));
            producerA.flush(); // 브로커 로그에는 이미 기록된 상태로 만든다

            // B: A와 무관한 별개 트랜잭션. 같은 파티션에 쓰고 곧바로 커밋을 끝낸다.
            //    → offset 2 = B의 메시지, offset 3 = B의 커밋 마커
            producerB.initTransactions();
            producerB.beginTransaction();
            producerB.send(new ProducerRecord<>(TOPIC_BLOCKING, "fast-1", "B는 커밋을 끝냈다"));
            producerB.commitTransaction();

            // B는 분명히 커밋했지만, LSO가 A의 시작 오프셋(0)에 묶여 있어 아무것도 못 읽는다.
            // 안 보여야 하는 것을 검증하므로 기대 개수를 크게 잡아 타임아웃까지 기다린다.
            List<ConsumerRecord<String, String>> blocked =
                    readCommitted(TOPIC_BLOCKING, "lab02-blocked-" + System.nanoTime(), 99, 4000);

            long lsoBlocked = lastStableOffset(TOPIC_BLOCKING, 0);
            long hwBlocked = highWatermark(TOPIC_BLOCKING, 0);
            System.out.printf("  A 열림 / B 커밋 완료 : LSO=%d, HW=%d%n", lsoBlocked, hwBlocked);
            printRecords("read_committed", blocked);

            assertThat(blocked)
                    .as("B는 커밋을 끝냈는데도, 앞서 열린 A 때문에 read_committed 소비자는 한 건도 못 읽는다")
                    .isEmpty();
            assertThat(lsoBlocked)
                    .as("LSO는 결말이 안 난 가장 오래된 트랜잭션(A)의 시작 오프셋 0에 묶인다")
                    .isEqualTo(0);
            assertThat(hwBlocked)
                    .as("A 메시지 2개 + B 메시지 1개 + B 커밋 마커 1개 = 4 (HW는 앞으로 나간다)")
                    .isEqualTo(4);

            // A를 커밋해서 막힌 것을 푼다. → offset 4 = A의 커밋 마커
            producerA.commitTransaction();
        }

        List<ConsumerRecord<String, String>> unblocked =
                readCommitted(TOPIC_BLOCKING, "lab02-unblocked-" + System.nanoTime(), 3, 5000);

        long lsoAfter = lastStableOffset(TOPIC_BLOCKING, 0);
        long hwAfter = highWatermark(TOPIC_BLOCKING, 0);
        System.out.printf("  A 커밋 후            : LSO=%d, HW=%d%n", lsoAfter, hwAfter);
        printRecords("read_committed", unblocked);
        printSeparator();

        assertThat(unblocked)
                .as("A를 끝내는 순간 LSO가 풀리면서 A(2건)와 B(1건)가 한꺼번에 쏟아진다")
                .hasSize(3);
        assertThat(lsoAfter)
                .as("A 메시지 2개 + B 메시지 1개 + B 마커 1개 + A 마커 1개 = 5, LSO가 HW까지 따라잡는다")
                .isEqualTo(5);
        assertThat(hwAfter)
                .as("열린 트랜잭션이 없으면 LSO와 HW가 같아진다")
                .isEqualTo(lsoAfter);
    }

    @Test
    @Order(2)
    @DisplayName("Q2. 같은 상황에서 read_uncommitted는 A/B를 즉시 본다")
    void readUncommittedSeesEverythingImmediately() throws Exception {
        try (KafkaProducer<String, String> producerA = transactionalProducer(TX_ID_ISO_A);
             KafkaProducer<String, String> producerB = transactionalProducer(TX_ID_ISO_B)) {

            // Q1과 동일한 상황을 새 토픽에 다시 만든다. A는 열어둔 채, B는 커밋 완료.
            producerA.initTransactions();
            producerA.beginTransaction();
            producerA.send(new ProducerRecord<>(TOPIC_ISOLATION, "slow-1", "A 진행 중"));
            producerA.send(new ProducerRecord<>(TOPIC_ISOLATION, "slow-2", "A 진행 중"));
            producerA.flush();

            producerB.initTransactions();
            producerB.beginTransaction();
            producerB.send(new ProducerRecord<>(TOPIC_ISOLATION, "fast-1", "B 커밋 완료"));
            producerB.commitTransaction();

            List<ConsumerRecord<String, String>> uncommitted =
                    readUncommitted(TOPIC_ISOLATION, "lab02-iso-u-" + System.nanoTime(), 3, 5000);
            List<ConsumerRecord<String, String>> committed =
                    readCommitted(TOPIC_ISOLATION, "lab02-iso-c-" + System.nanoTime(), 99, 4000);

            System.out.printf("  LSO=%d, HW=%d — 두 소비자가 보는 '끝'이 다르다%n",
                    lastStableOffset(TOPIC_ISOLATION, 0), highWatermark(TOPIC_ISOLATION, 0));
            printRecords("read_uncommitted", uncommitted);
            printRecords("read_committed", committed);

            assertThat(uncommitted)
                    .as("read_uncommitted는 HW까지 읽으므로 A(진행 중) 2건 + B(커밋) 1건을 즉시 본다")
                    .hasSize(3);
            assertThat(committed)
                    .as("read_committed는 LSO까지만 읽으므로 같은 순간에 0건이다")
                    .isEmpty();

            // A를 abort해서 정리한다. 열어둔 트랜잭션을 남기면 다음 테스트가 LSO에 막힌다.
            producerA.abortTransaction();
        }

        List<ConsumerRecord<String, String>> afterAbort =
                readCommitted(TOPIC_ISOLATION, "lab02-iso-abort-" + System.nanoTime(), 1, 5000);
        printRecords("read_committed(A abort 후)", afterAbort);
        printSeparator();

        assertThat(afterAbort)
                .as("A를 abort하면 LSO가 풀리지만, A의 메시지 2건은 걸러지고 B의 1건만 남는다")
                .hasSize(1);
        assertThat(afterAbort.get(0).offset())
                .as("B의 메시지는 offset 2에 그대로 있었다 — 막혔던 것이지 사라진 게 아니다")
                .isEqualTo(2);
    }

    @Test
    @Order(3)
    @DisplayName("Q3. 커밋 마커가 오프셋을 차지해 '메시지 수 < 끝 오프셋'이 된다")
    void controlBatchesConsumeOffsets() throws Exception {
        try (KafkaProducer<String, String> producer = transactionalProducer(TX_ID_MARKER)) {
            producer.initTransactions();

            // 트랜잭션 1건당: 메시지 MSG_PER_TX개 + 커밋 마커 1개 = (MSG_PER_TX + 1) 오프셋 소비
            for (int round = 1; round <= TX_ROUNDS; round++) {
                producer.beginTransaction();
                for (int i = 1; i <= MSG_PER_TX; i++) {
                    producer.send(new ProducerRecord<>(TOPIC_MARKER,
                            "r" + round + "-m" + i, "round-" + round));
                }
                producer.commitTransaction();

                long hw = highWatermark(TOPIC_MARKER, 0);
                System.out.printf("  %d번째 트랜잭션 커밋 후: 누적 메시지=%d, HW=%d%n",
                        round, round * MSG_PER_TX, hw);
                assertThat(hw)
                        .as("%d라운드 × (메시지 %d개 + 마커 1개)", round, MSG_PER_TX)
                        .isEqualTo((long) round * (MSG_PER_TX + 1));
            }
        }

        int expectedMessages = TX_ROUNDS * MSG_PER_TX;              // 3 × 2 = 6
        long expectedEndOffset = (long) TX_ROUNDS * (MSG_PER_TX + 1); // 3 × 3 = 9

        List<ConsumerRecord<String, String>> consumed =
                readCommitted(TOPIC_MARKER, "lab02-marker-" + System.nanoTime(), expectedMessages, 6000);

        long hw = highWatermark(TOPIC_MARKER, 0);
        long lso = lastStableOffset(TOPIC_MARKER, 0);
        long markerCount = hw - consumed.size();

        printRecords("read_committed", consumed);
        System.out.printf("  소비한 레코드 수=%d, HW=%d, LSO=%d → 마커가 먹은 오프셋=%d%n",
                consumed.size(), hw, lso, markerCount);
        System.out.println("  → lag 계산이 (끝 오프셋 - 커밋 오프셋)이면 마커 수만큼 0으로 안 떨어지는 것처럼 보인다");
        printSeparator();

        assertThat(consumed)
                .as("실제로 소비되는 것은 사용자 메시지 %d개뿐이다", expectedMessages)
                .hasSize(expectedMessages);
        assertThat(hw)
                .as("끝 오프셋은 메시지 %d개 + 커밋 마커 %d개 = %d이다",
                        expectedMessages, TX_ROUNDS, expectedEndOffset)
                .isEqualTo(expectedEndOffset);
        assertThat(lso)
                .as("열린 트랜잭션이 없으므로 LSO는 HW와 같다")
                .isEqualTo(hw);
        assertThat(markerCount)
                .as("메시지 수와 끝 오프셋의 차이는 정확히 커밋한 트랜잭션 수(%d)와 같다", TX_ROUNDS)
                .isEqualTo(TX_ROUNDS);
        assertThat((long) consumed.size())
                .as("모니터링 착시의 원인: 실제 메시지 수 < 끝 오프셋")
                .isLessThan(hw);
    }
}
