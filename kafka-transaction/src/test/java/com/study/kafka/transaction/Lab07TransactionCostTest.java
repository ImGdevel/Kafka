package com.study.kafka.transaction;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 07 — EOS(정확히 한 번)의 비용
 *
 * 검증 명제: "정확히 한 번은 공짜가 아니다 — 비용은 메시지 수가 아니라 트랜잭션 수에 비례한다"
 *
 * Lab01~06은 트랜잭션이 무엇을 "보장"하는지를 봤다. Lab07은 그 대가를 측정한다.
 * 트랜잭션 1건마다 다음 비용이 고정으로 붙는다.
 *   - 코디네이터 왕복: AddPartitionsToTxn(첫 파티션 접촉 시) + EndTxn(커밋 시)
 *   - __transaction_state 내부 토픽에 상태 기록 (RF=3, acks=all)
 *   - 트랜잭션이 건드린 "파티션마다" control batch(커밋 마커) 1개 쓰기
 * 이 비용은 트랜잭션 안에 메시지가 1건이든 100건이든 거의 같다.
 * 따라서 "메시지 1건마다 트랜잭션 1건"으로 짜면 오버헤드가 지배하고,
 * 여러 건을 한 트랜잭션에 묶으면 그 고정비가 메시지 수만큼 상각(amortize)된다.
 *
 * Q1. 같은 메시지 수를 (a) 일반 Producer, (b) 트랜잭션 Producer(전체를 한 트랜잭션으로) 보낸다.
 *     트랜잭션 쪽이 느리지만 파국적으로 느리지는 않다 — 고정비가 전체에 1번만 붙기 때문이다.
 * Q2. 핵심 실험. 같은 메시지 수를 (a) 1건당 트랜잭션 1건, (b) N건당 트랜잭션 1건으로 보낸다.
 *     "느린 이유"를 추측이 아니라 로그에 남은 control batch 수로 설명한다.
 *     (a)는 마커가 메시지 수만큼, (b)는 트랜잭션 수만큼 생긴다.
 * Q3. 트랜잭션이 여러 파티션에 걸치면 마커가 "파티션마다" 하나씩 생긴다.
 *     파티션 3개짜리 토픽에 한 트랜잭션으로 쓰면 마커는 3개다. 1파티션 토픽(마커 1개)과 대조한다.
 *
 * 측정 원칙 — "속도는 출력만, 단정은 결정론적 값만":
 *   로컬 Docker 환경의 처리량은 JIT 상태·디스크·컨테이너 스케줄링에 따라 크게 흔들린다.
 *   따라서 "(b)가 (a)보다 빠르다" 같은 성능 부등식은 절대 assertThat으로 단정하지 않는다.
 *   속도(msg/sec)는 사람이 읽도록 출력만 하고, assertThat으로 단정하는 것은
 *   재현 가능한 결정론적 값 — 마커 개수, 끝 오프셋, 메시지 수 — 뿐이다.
 *   단, Q2의 "마커 개수 차이"는 프로토콜상 결정론적이므로 단정한다.
 *
 * 측정 왜곡을 줄이기 위해 본 측정 전에 별도 워밍업 토픽으로 소량 전송을 1회 수행한다.
 * (첫 메타데이터 요청·커넥션 수립·클래스 로딩이 첫 시나리오에만 부과되는 것을 막는다)
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab07*' --info
 */
@Tag("lab")
@DisplayName("Lab 07 — EOS의 비용")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab07TransactionCostTest {

    // ── 토픽 ──────────────────────────────────────────────────────
    // 시나리오마다 새 토픽을 쓴다. 오프셋 계산이 섞이면 마커 수를 셀 수 없다.
    private static final String TOPIC_WARMUP = "tx-lab07-warmup";
    private static final String TOPIC_Q1_PLAIN = "tx-lab07-q1-plain";
    private static final String TOPIC_Q1_TX = "tx-lab07-q1-tx";
    private static final String TOPIC_Q2_PER_MSG = "tx-lab07-q2-per-msg";
    private static final String TOPIC_Q2_BATCHED = "tx-lab07-q2-batched";
    private static final String TOPIC_Q3_SINGLE = "tx-lab07-q3-single-part";
    private static final String TOPIC_Q3_MULTI = "tx-lab07-q3-multi-part";

    // ── transactional.id ─────────────────────────────────────────
    private static final String TX_ID_WARMUP = "tx-lab07-warmup-writer";
    private static final String TX_ID_Q1 = "tx-lab07-q1-writer";
    private static final String TX_ID_Q2_PER_MSG = "tx-lab07-q2-per-msg-writer";
    private static final String TX_ID_Q2_BATCHED = "tx-lab07-q2-batched-writer";
    private static final String TX_ID_Q3_SINGLE = "tx-lab07-q3-single-writer";
    private static final String TX_ID_Q3_MULTI = "tx-lab07-q3-multi-writer";

    // ── 실험 규모 ─────────────────────────────────────────────────
    /** Q1 메시지 수. 전송 자체가 지배하도록 넉넉히 잡는다(로컬에서 각 1~2초 내외). */
    private static final int Q1_MSG_COUNT = 3_000;

    /**
     * Q2 메시지 수. "1건당 트랜잭션 1건"은 메시지마다 코디네이터를 두 번 왕복하므로 매우 느리다.
     * 로컬에서 수 초 안에 끝나도록 작게 잡는다.
     */
    private static final int Q2_MSG_COUNT = 300;
    /** Q2 (b)에서 한 트랜잭션에 묶을 메시지 수. 300 / 100 = 트랜잭션 3건. */
    private static final int Q2_BATCH_SIZE = 100;
    private static final int Q2_TX_COUNT = Q2_MSG_COUNT / Q2_BATCH_SIZE;

    /** Q3 파티션 수와 파티션당 메시지 수. 3 × 5 = 총 15건을 단 하나의 트랜잭션으로 쓴다. */
    private static final int Q3_PARTITIONS = 3;
    private static final int Q3_MSG_PER_PARTITION = 5;
    private static final int Q3_TOTAL_MSG = Q3_PARTITIONS * Q3_MSG_PER_PARTITION;

    /** 워밍업 전송 건수 */
    private static final int WARMUP_MSG_COUNT = 50;

    // ── 측정값 보관 (@AfterAll 요약 출력용, 단정에는 쓰지 않는다) ──
    private static long q1PlainMs;
    private static long q1TxMs;
    private static long q2PerMsgMs;
    private static long q2BatchedMs;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 07: EOS의 비용",
                "비용은 메시지 수가 아니라 트랜잭션 수(그리고 트랜잭션이 건드린 파티션 수)에 비례한다");

        createTopic(TOPIC_WARMUP, 1, (short) 3);
        createTopic(TOPIC_Q1_PLAIN, 1, (short) 3);
        createTopic(TOPIC_Q1_TX, 1, (short) 3);
        createTopic(TOPIC_Q2_PER_MSG, 1, (short) 3);
        createTopic(TOPIC_Q2_BATCHED, 1, (short) 3);
        createTopic(TOPIC_Q3_SINGLE, 1, (short) 3);
        // Q3 대조군만 파티션 3개 — 마커가 파티션마다 생기는 것을 보기 위함
        createTopic(TOPIC_Q3_MULTI, Q3_PARTITIONS, (short) 3);

        warmUp();
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_WARMUP);
        deleteTopic(TOPIC_Q1_PLAIN);
        deleteTopic(TOPIC_Q1_TX);
        deleteTopic(TOPIC_Q2_PER_MSG);
        deleteTopic(TOPIC_Q2_BATCHED);
        deleteTopic(TOPIC_Q3_SINGLE);
        deleteTopic(TOPIC_Q3_MULTI);
        printSummary();
    }

    // ─────────────────────────────────────────────────────────────
    // Q1
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Q1. 일반 Producer vs 트랜잭션 Producer — 트랜잭션이 1건이면 비용도 1건분이다")
    void plainVersusTransactionalThroughput() {
        // (a) 일반 Producer — 트랜잭션 없음. 마커도 생기지 않는다.
        //     Kafka 3.x 기본값(enable.idempotence=true, acks=all)이 적용되므로 중복 없이 정확히 N건이 남는다.
        try (KafkaProducer<String, String> plain = plainProducer()) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < Q1_MSG_COUNT; i++) {
                plain.send(new ProducerRecord<>(TOPIC_Q1_PLAIN, "k-" + i, "v-" + i));
            }
            plain.flush();
            q1PlainMs = System.currentTimeMillis() - start;
        }

        // (b) 트랜잭션 Producer — 전체 Q1_MSG_COUNT건을 단 하나의 트랜잭션으로 감싼다.
        //     initTransactions()는 코디네이터 탐색 + PID 발급이라 "시작 비용"에 해당하므로
        //     전송 처리량과 분리해서 따로 측정/출력한다.
        long initMs;
        try (KafkaProducer<String, String> tx = transactionalProducer(TX_ID_Q1)) {
            long initStart = System.currentTimeMillis();
            tx.initTransactions();
            initMs = System.currentTimeMillis() - initStart;

            long start = System.currentTimeMillis();
            tx.beginTransaction();
            for (int i = 0; i < Q1_MSG_COUNT; i++) {
                tx.send(new ProducerRecord<>(TOPIC_Q1_TX, "k-" + i, "v-" + i));
            }
            tx.commitTransaction(); // 내부적으로 flush + EndTxn + 마커 쓰기까지 기다린다
            q1TxMs = System.currentTimeMillis() - start;
        }

        long plainEnd = highWatermark(TOPIC_Q1_PLAIN, 0);
        long txEnd = highWatermark(TOPIC_Q1_TX, 0);

        // 속도는 "출력만" 한다 — 단정하지 않는다.
        printResult("Q1(a) 일반 Producer", Q1_MSG_COUNT, q1PlainMs);
        printResult("Q1(b) 트랜잭션 1건", Q1_MSG_COUNT, q1TxMs);
        System.out.printf("  initTransactions() 만의 소요: %dms (코디네이터 탐색 + PID 발급, 1회성)%n", initMs);
        System.out.printf("  끝 오프셋 — 일반=%d(마커 0개), 트랜잭션=%d(마커 %d개)%n",
                plainEnd, txEnd, txEnd - Q1_MSG_COUNT);
        System.out.println("  → 메시지 3,000건을 트랜잭션 1건으로 묶으면 고정비도 1건분뿐이다.");
        System.out.println("     느려지긴 해도 '파국적으로' 느려지지 않는 이유가 이것이다.");
        printSeparator();

        // 단정은 결정론적 값만.
        assertThat(plainEnd)
                .as("일반 Producer는 control batch를 쓰지 않으므로 끝 오프셋 = 메시지 수 %d", Q1_MSG_COUNT)
                .isEqualTo(Q1_MSG_COUNT);
        assertThat(txEnd)
                .as("트랜잭션 1건이므로 끝 오프셋 = 메시지 %d + 커밋 마커 1", Q1_MSG_COUNT)
                .isEqualTo(Q1_MSG_COUNT + 1L);
        assertThat(lastStableOffset(TOPIC_Q1_TX, 0))
                .as("커밋을 끝냈으므로 LSO는 HW까지 따라잡는다")
                .isEqualTo(txEnd);
    }

    // ─────────────────────────────────────────────────────────────
    // Q2 — 핵심 실험
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Q2. 트랜잭션 경계를 어디에 두느냐 — 메시지 1건당 1트랜잭션 vs 100건당 1트랜잭션")
    void transactionBoundaryDominatesCost() {
        // (a) 최악의 설계: 메시지 1건마다 begin → send → commit.
        //     메시지 하나를 위해 AddPartitionsToTxn + EndTxn + 마커 쓰기를 매번 치른다.
        try (KafkaProducer<String, String> perMsg = transactionalProducer(TX_ID_Q2_PER_MSG)) {
            perMsg.initTransactions();
            long start = System.currentTimeMillis();
            for (int i = 0; i < Q2_MSG_COUNT; i++) {
                perMsg.beginTransaction();
                perMsg.send(new ProducerRecord<>(TOPIC_Q2_PER_MSG, "k-" + i, "v-" + i));
                perMsg.commitTransaction();
            }
            q2PerMsgMs = System.currentTimeMillis() - start;
        }

        // (b) 같은 메시지 수, 트랜잭션 경계만 바꾼다: Q2_BATCH_SIZE건마다 커밋.
        //     고정비를 Q2_BATCH_SIZE개 메시지가 나눠 부담한다.
        try (KafkaProducer<String, String> batched = transactionalProducer(TX_ID_Q2_BATCHED)) {
            batched.initTransactions();
            long start = System.currentTimeMillis();
            for (int t = 0; t < Q2_TX_COUNT; t++) {
                batched.beginTransaction();
                for (int i = 0; i < Q2_BATCH_SIZE; i++) {
                    int seq = t * Q2_BATCH_SIZE + i;
                    batched.send(new ProducerRecord<>(TOPIC_Q2_BATCHED, "k-" + seq, "v-" + seq));
                }
                batched.commitTransaction();
            }
            q2BatchedMs = System.currentTimeMillis() - start;
        }

        long perMsgEnd = highWatermark(TOPIC_Q2_PER_MSG, 0);
        long batchedEnd = highWatermark(TOPIC_Q2_BATCHED, 0);
        long perMsgMarkers = perMsgEnd - Q2_MSG_COUNT;   // 마커 수 = 끝 오프셋 − 보낸 메시지 수
        long batchedMarkers = batchedEnd - Q2_MSG_COUNT;

        // 속도는 출력만.
        printResult("Q2(a) 1건당 1트랜잭션", Q2_MSG_COUNT, q2PerMsgMs);
        printResult("Q2(b) " + Q2_BATCH_SIZE + "건당 1트랜잭션", Q2_MSG_COUNT, q2BatchedMs);

        // 느린 "이유"는 추측이 아니라 로그에 남은 control batch 수로 설명된다.
        System.out.printf("  (a) 끝 오프셋=%d = 메시지 %d + 마커 %d개  (트랜잭션 %d건)%n",
                perMsgEnd, Q2_MSG_COUNT, perMsgMarkers, Q2_MSG_COUNT);
        System.out.printf("  (b) 끝 오프셋=%d = 메시지 %d + 마커 %d개  (트랜잭션 %d건)%n",
                batchedEnd, Q2_MSG_COUNT, batchedMarkers, Q2_TX_COUNT);
        System.out.printf("  → 같은 메시지 수인데 control batch가 %d배 차이난다. 오버헤드의 정체가 이것이다.%n",
                perMsgMarkers / Math.max(batchedMarkers, 1));
        System.out.println("     로그 공간도, 코디네이터 왕복 횟수도 '트랜잭션 수'를 따라간다.");
        printSeparator();

        // 단정은 결정론적 값만. 마커 개수는 프로토콜상 확정값이므로 단정한다.
        assertThat(perMsgMarkers)
                .as("1건당 1트랜잭션이면 마커도 메시지 수(%d)만큼 생긴다", Q2_MSG_COUNT)
                .isEqualTo(Q2_MSG_COUNT);
        assertThat(perMsgEnd)
                .as("끝 오프셋 = 메시지 %d + 마커 %d", Q2_MSG_COUNT, Q2_MSG_COUNT)
                .isEqualTo(Q2_MSG_COUNT * 2L);
        assertThat(batchedMarkers)
                .as("%d건씩 묶으면 마커는 트랜잭션 수(%d)만큼만 생긴다", Q2_BATCH_SIZE, Q2_TX_COUNT)
                .isEqualTo(Q2_TX_COUNT);
        assertThat(batchedEnd)
                .as("끝 오프셋 = 메시지 %d + 마커 %d", Q2_MSG_COUNT, Q2_TX_COUNT)
                .isEqualTo(Q2_MSG_COUNT + (long) Q2_TX_COUNT);
        assertThat(perMsgMarkers)
                .as("마커 수 차이는 결정론적이다 — 비용이 메시지 수가 아니라 트랜잭션 수에 비례한다는 증거")
                .isGreaterThan(batchedMarkers);
        assertThat(readCommitted(TOPIC_Q2_BATCHED, "lab07-q2-" + System.nanoTime(), Q2_MSG_COUNT, 10_000))
                .as("마커는 오프셋만 차지할 뿐 소비자에게는 보이지 않는다 — 실제 메시지는 %d건 그대로", Q2_MSG_COUNT)
                .hasSize(Q2_MSG_COUNT);
    }

    // ─────────────────────────────────────────────────────────────
    // Q3
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Q3. 트랜잭션이 걸친 파티션마다 커밋 마커가 하나씩 생긴다")
    void markerCostScalesWithPartitionCount() {
        // (a) 1파티션 토픽 — 총 Q3_TOTAL_MSG건을 트랜잭션 1건으로 쓴다. 마커는 1개.
        try (KafkaProducer<String, String> single = transactionalProducer(TX_ID_Q3_SINGLE)) {
            single.initTransactions();
            single.beginTransaction();
            for (int i = 0; i < Q3_TOTAL_MSG; i++) {
                // 파티션을 명시해 배치가 흔들리지 않게 한다(오프셋 계산을 결정론적으로 유지).
                single.send(new ProducerRecord<>(TOPIC_Q3_SINGLE, 0, "k-" + i, "v-" + i));
            }
            single.commitTransaction();
        }

        // (b) 3파티션 토픽 — 같은 트랜잭션 1건이 파티션 3개를 모두 건드린다.
        //     커밋 시 브로커는 "각 파티션 로그에" 마커를 따로 써야 한다. 마커 3개.
        try (KafkaProducer<String, String> multi = transactionalProducer(TX_ID_Q3_MULTI)) {
            multi.initTransactions();
            multi.beginTransaction();
            for (int p = 0; p < Q3_PARTITIONS; p++) {
                for (int i = 0; i < Q3_MSG_PER_PARTITION; i++) {
                    multi.send(new ProducerRecord<>(TOPIC_Q3_MULTI, p, "p" + p + "-k" + i, "v-" + i));
                }
            }
            multi.commitTransaction();
        }

        long singleEnd = highWatermark(TOPIC_Q3_SINGLE, 0);
        long singleMarkers = singleEnd - Q3_TOTAL_MSG;

        System.out.printf("  (a) 1파티션 토픽: 메시지 %d건 / 트랜잭션 1건%n", Q3_TOTAL_MSG);
        System.out.printf("      p0: 끝 오프셋=%d = 메시지 %d + 마커 %d%n",
                singleEnd, Q3_TOTAL_MSG, singleMarkers);

        System.out.printf("  (b) %d파티션 토픽: 메시지 %d건 / 트랜잭션 1건 (파티션당 %d건)%n",
                Q3_PARTITIONS, Q3_TOTAL_MSG, Q3_MSG_PER_PARTITION);
        long multiTotalEnd = 0;
        long multiTotalMarkers = 0;
        for (int p = 0; p < Q3_PARTITIONS; p++) {
            long end = highWatermark(TOPIC_Q3_MULTI, p);
            long markers = end - Q3_MSG_PER_PARTITION;
            multiTotalEnd += end;
            multiTotalMarkers += markers;
            System.out.printf("      p%d: 끝 오프셋=%d = 메시지 %d + 마커 %d%n",
                    p, end, Q3_MSG_PER_PARTITION, markers);

            // 파티션마다 개별로 단정한다 — "마커가 파티션 수만큼 존재한다"의 근거.
            assertThat(end)
                    .as("파티션 %d: 메시지 %d건 + 그 파티션의 커밋 마커 1개", p, Q3_MSG_PER_PARTITION)
                    .isEqualTo(Q3_MSG_PER_PARTITION + 1L);
            assertThat(markers)
                    .as("파티션 %d에도 마커가 정확히 1개 있다", p)
                    .isEqualTo(1);
        }

        System.out.printf("  → 같은 '트랜잭션 1건'인데 마커 총합은 %d개 vs %d개 — 파티션 수만큼 늘어난다.%n",
                singleMarkers, multiTotalMarkers);
        System.out.println("     트랜잭션 비용 = (코디네이터 왕복) + (건드린 파티션 수 × 마커 쓰기).");
        System.out.println("     그래서 파티션을 넓게 흩뿌리는 트랜잭션은 같은 메시지 수라도 더 비싸다.");
        printSeparator();

        assertThat(singleMarkers)
                .as("1파티션 토픽에 쓴 트랜잭션 1건의 마커는 1개다")
                .isEqualTo(1);
        assertThat(multiTotalMarkers)
                .as("트랜잭션은 1건이지만 파티션 %d개를 건드렸으므로 마커 총합은 %d개다",
                        Q3_PARTITIONS, Q3_PARTITIONS)
                .isEqualTo(Q3_PARTITIONS);
        assertThat(multiTotalEnd)
                .as("3파티션 끝 오프셋 총합 = 메시지 %d + 마커 %d", Q3_TOTAL_MSG, Q3_PARTITIONS)
                .isEqualTo(Q3_TOTAL_MSG + (long) Q3_PARTITIONS);
        assertThat(multiTotalMarkers)
                .as("메시지 수가 같아도 파티션이 많으면 control batch가 더 많이 쓰인다")
                .isGreaterThan(singleMarkers);
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼 (TxHelper에는 처리량 출력 헬퍼가 없어 이 클래스에만 둔다)
    // ─────────────────────────────────────────────────────────────

    /**
     * 처리량 결과를 저장소의 기존 관례(LabHelper.printResult)와 같은 형식으로 출력한다.
     * 어디까지나 "출력"이다 — 이 값으로 성능 부등식을 단정하지 않는다.
     */
    private static void printResult(String label, int count, long elapsedMs) {
        long rate = count * 1000L / Math.max(elapsedMs, 1);
        System.out.printf("  [%-25s] %,d msgs / %4dms → %,8d msg/sec%n",
                label, count, elapsedMs, rate);
    }

    /**
     * 본 측정 전 워밍업. 첫 메타데이터 요청·커넥션 수립·JIT 컴파일 비용이
     * 맨 앞 시나리오에만 부과되어 결과를 왜곡하는 것을 막는다.
     * 워밍업 결과는 별도 토픽에 남으므로 오프셋 계산에 섞이지 않는다.
     */
    private static void warmUp() {
        try (KafkaProducer<String, String> plain = plainProducer()) {
            for (int i = 0; i < WARMUP_MSG_COUNT; i++) {
                plain.send(new ProducerRecord<>(TOPIC_WARMUP, "warm-" + i, "warm"));
            }
            plain.flush();
        }
        try (KafkaProducer<String, String> tx = transactionalProducer(TX_ID_WARMUP)) {
            tx.initTransactions();
            tx.beginTransaction();
            for (int i = 0; i < WARMUP_MSG_COUNT; i++) {
                tx.send(new ProducerRecord<>(TOPIC_WARMUP, "warm-tx-" + i, "warm"));
            }
            tx.commitTransaction();
        }
        System.out.printf("  워밍업 완료 (일반 %d건 + 트랜잭션 %d건) — 이후 측정에서 첫 요청 비용을 제외한다%n",
                WARMUP_MSG_COUNT, WARMUP_MSG_COUNT);
        printSeparator();
    }

    /** 마지막 요약. 측정치는 참고용이며 환경에 따라 흔들린다. */
    private static void printSummary() {
        if (q1PlainMs == 0 && q1TxMs == 0 && q2PerMsgMs == 0 && q2BatchedMs == 0) return;

        System.out.println();
        System.out.println("  ── Lab 07 요약 ────────────────────────────────────");
        System.out.println("  - 트랜잭션의 고정비: 코디네이터 왕복(AddPartitionsToTxn/EndTxn)");
        System.out.println("    + __transaction_state 기록 + 건드린 파티션마다 control batch 1개");
        System.out.println("  - 이 고정비는 트랜잭션 1건당 붙는다. 메시지 1건당이 아니다.");
        System.out.println("  - 따라서 처리량을 좌우하는 것은 '메시지 수'가 아니라 '트랜잭션 경계'다.");
        System.out.println("  - 실무 지침: 커밋 주기를 늘려 고정비를 상각하되, 그만큼 LSO가 늦게 풀려");
        System.out.println("    read_committed 소비자의 지연이 커진다(Lab02). 처리량 ↔ 지연 트레이드오프.");
        System.out.println("  * 위 msg/sec 값은 로컬 환경 참고치일 뿐이며 단정의 근거로 쓰지 않았다.");
        printSeparator();
    }
}
