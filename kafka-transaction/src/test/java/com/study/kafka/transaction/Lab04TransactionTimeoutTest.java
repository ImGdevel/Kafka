package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.TransactionDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.InvalidPidMappingException;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.InvalidTxnStateException;
import org.apache.kafka.common.errors.InvalidTxnTimeoutException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownProducerIdException;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 04 — transaction.timeout.ms와 코디네이터의 강제 abort
 *
 * 검증 명제: "트랜잭션을 열어둔 채 방치하면 코디네이터가 대신 끝낸다"
 *
 * Lab 02에서 열린 트랜잭션 하나가 LSO를 붙잡아 같은 파티션의 무관한 메시지까지 막는 것을 봤다.
 * 그렇다면 그 상태가 영원히 지속되는가? 아니다.
 * 트랜잭션 코디네이터는 transaction.timeout.ms를 넘긴 트랜잭션을 강제로 abort시키고
 * 중단 마커(control batch)를 로그에 써버린다. 그 순간 LSO가 풀린다.
 * 이것이 Lab 02의 head-of-line blocking이 무한정 이어지지 않는 이유다.
 *
 * 그리고 이 타임아웃은 클라이언트가 마음대로 늘릴 수 없다. 브로커의 transaction.max.timeout.ms가
 * 상한이며, 그보다 큰 값을 요청하면 initTransactions() 단계에서 거절당한다.
 * "클라이언트가 트랜잭션을 무한정 붙잡아둘 수 없다"는 안전장치가 양쪽에 걸려 있는 셈이다.
 *
 * Q1. transaction.timeout.ms를 2초로 준 Producer가 begin + send + flush 후 그대로 방치된다.
 *     코디네이터가 abort시킨 뒤 이 Producer가 commitTransaction()을 호출하면 실패하는가?
 *     (코디네이터는 강제 abort하면서 epoch를 올리므로 이 Producer는 이미 펜싱된 상태다)
 *     그리고 메시지는 read_committed에 보이지 않는가?
 * Q2. 아무도 개입하지 않아도 LSO가 저절로 풀리는가?
 *     Lab 01 Q2에서는 "같은 txId로 새 Producer가 떠서" LSO가 풀렸다. 여기서는 아무도 뜨지 않는다.
 *     타임아웃 후 LSO 전진을 폴링으로 관측하고, 최종적으로 LSO == HW,
 *     HW == 메시지 수 + 중단 마커 1임을 확인한다. read_uncommitted로는 메시지가 여전히 보인다.
 * Q3. 브로커의 transaction.max.timeout.ms(기본 900000ms = 15분)보다 큰 값을 요청하면
 *     initTransactions()에서 즉시 거부되는가?
 *
 * 주의 — 이 Lab은 대기 시간이 길다.
 * 코디네이터의 만료 처리는 즉시 일어나지 않는다. 브로커는 백그라운드 스레드로
 * transaction.abort.timed.out.transaction.cleanup.interval.ms(기본 10초) 주기마다
 * 만료된 트랜잭션을 훑어서 abort시킨다. 따라서 "타임아웃 2초 = 2초 뒤 abort"가 아니라
 * "타임아웃 2초 + 최대 청소 주기 10초"가 실제 관측 시점이다.
 * 고정 Thread.sleep()으로 단정하면 깨지기 쉬우므로 LSO 전진을 폴링해서 기다린다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab04*' --info
 */
@Tag("lab")
@DisplayName("Lab 04 — transaction.timeout.ms")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab04TransactionTimeoutTest {

    private static final String TOPIC_EXPIRE = "tx-lab04-expire";
    private static final String TOPIC_MARKER = "tx-lab04-marker";

    private static final String TX_ID_EXPIRE   = "tx-lab04-expire-worker";
    private static final String TX_ID_MARKER   = "tx-lab04-marker-worker";
    private static final String TX_ID_TOO_LONG = "tx-lab04-too-long-worker";

    /** 실습용으로 아주 짧게 잡은 트랜잭션 타임아웃. 기본값은 60000ms다. */
    private static final int TX_TIMEOUT_MS = 2000;

    /**
     * 코디네이터의 강제 abort를 기다리는 최대 시간.
     * 이론상 최악은 (타임아웃 2초 + 청소 주기 10초)이지만, 브로커 부하나 코디네이터 리더 이동을
     * 감안해 넉넉하게 60초까지 허용한다. 정상이라면 10초 안팎에서 끝난다.
     */
    private static final long ABORT_WAIT_MS = 60_000;

    /** max.block.ms 기본값은 60초라 펜싱 상황에서 테스트가 오래 매달릴 수 있어 줄여 둔다. */
    private static final String MAX_BLOCK_MS = "20000";

    /** Q2에서 열어둔 채 방치할 메시지 수 */
    private static final int Q2_MESSAGE_COUNT = 2;

    /** 브로커 transaction.max.timeout.ms 기본값 (docker-compose에서 별도 설정하지 않았다) */
    private static final int BROKER_MAX_TX_TIMEOUT_MS = 900_000;
    /** 브로커 상한을 일부러 넘긴 값 */
    private static final int TOO_LONG_TIMEOUT_MS = 1_000_000;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 04: transaction.timeout.ms와 코디네이터의 강제 abort",
                "트랜잭션을 열어둔 채 방치하면 코디네이터가 대신 끝낸다");
        System.out.println("  ※ 코디네이터의 만료 청소는 주기(기본 10초) 단위라 이 Lab은 대기 시간이 깁니다.");
        // 파티션 1개 — LSO/HW 관측을 단일 파티션에서 단순하게 하기 위함
        createTopic(TOPIC_EXPIRE, 1, (short) 3);
        createTopic(TOPIC_MARKER, 1, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_EXPIRE);
        deleteTopic(TOPIC_MARKER);
    }

    @Test
    @Order(1)
    @DisplayName("Q1. 타임아웃된 트랜잭션은 코디네이터가 abort시키고, 뒤늦은 commitTransaction()은 실패한다")
    void expiredTransactionCannotBeCommittedByItsOwnProducer() throws Exception {
        KafkaProducer<String, String> lazy = shortTimeoutProducer(TX_ID_EXPIRE);
        Throwable commitError = null;
        long lsoAfterAbort;
        long hwAfterAbort;

        try {
            lazy.initTransactions();
            lazy.beginTransaction();
            lazy.send(new ProducerRecord<>(TOPIC_EXPIRE, "lazy", "커밋도 중단도 하지 않고 방치한다"));
            // flush로 브로커 로그에는 이미 기록된 상태로 만든다. 트랜잭션만 결말이 안 난 상태다.
            lazy.flush();

            long lsoBefore = lastStableOffset(TOPIC_EXPIRE, 0);
            long hwBefore = highWatermark(TOPIC_EXPIRE, 0);
            System.out.printf("  방치 직후        : LSO=%d, HW=%d, 상태=%s%n",
                    lsoBefore, hwBefore, transactionState(TX_ID_EXPIRE));

            assertThat(lsoBefore)
                    .as("열린 트랜잭션의 시작 지점에서 LSO가 묶인다 (Lab 02와 같은 상황)")
                    .isEqualTo(0);
            assertThat(hwBefore)
                    .as("메시지 자체는 이미 복제되었으므로 HW는 앞으로 나가 있다")
                    .isEqualTo(1);

            // 1) 먼저 타임아웃 자체를 넘긴다.
            //    타임아웃 시계는 클라이언트의 beginTransaction()이 아니라
            //    코디네이터가 첫 AddPartitionsToTxn을 받아 트랜잭션을 연 시점부터 흐른다.
            System.out.printf("  transaction.timeout.ms=%dms 를 넘기도록 대기한다...%n", TX_TIMEOUT_MS);
            Thread.sleep(TX_TIMEOUT_MS + 500);

            // 2) 하지만 타임아웃이 지났다고 곧바로 abort되지는 않는다.
            //    브로커는 주기적으로(기본 10초) 만료 트랜잭션을 훑는다 → 폴링으로 기다린다.
            System.out.print("  코디네이터의 만료 청소 대기 ");
            lsoAfterAbort = awaitLsoAdvance(TOPIC_EXPIRE, lsoBefore, ABORT_WAIT_MS);
            hwAfterAbort = highWatermark(TOPIC_EXPIRE, 0);
            System.out.printf("  강제 abort 이후  : LSO=%d, HW=%d, 상태=%s%n",
                    lsoAfterAbort, hwAfterAbort, transactionState(TX_ID_EXPIRE));

            // 3) 아무것도 모르는 Producer가 이제야 커밋을 시도한다.
            //    코디네이터는 강제 abort와 동시에 epoch를 올렸으므로 이 Producer는 낡은 epoch를 들고 있다.
            //    즉 Lab 01의 좀비와 정확히 같은 처지다 — 다만 이번엔 다른 Producer가 아니라
            //    코디네이터 자신이 펜싱의 원인이다.
            try {
                lazy.commitTransaction();
            } catch (Throwable t) {
                commitError = t;
            }
        } finally {
            closeQuietly(lazy);
        }

        System.out.printf("  뒤늦은 커밋의 결과: %s%n", describeChain(commitError));

        // 안 보여야 하는 것을 검증하므로 기대 개수를 크게 잡아 타임아웃까지 기다린다
        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_EXPIRE, "lab04-expire-c-" + System.nanoTime(), 99, 4000);
        List<ConsumerRecord<String, String>> uncommitted =
                readUncommitted(TOPIC_EXPIRE, "lab04-expire-u-" + System.nanoTime(), 1, 4000);
        printRecords("read_committed", committed);
        printRecords("read_uncommitted", uncommitted);
        printSeparator();

        assertThat(lsoAfterAbort)
                .as("코디네이터가 중단 마커를 쓰면서 LSO가 풀렸어야 한다 (%dms 안에 관측되지 않았다면 "
                        + "브로커의 청소 주기 설정을 확인할 것)", ABORT_WAIT_MS)
                .isGreaterThan(0);
        assertThat(commitError)
                .as("이미 강제 abort된 트랜잭션을 뒤늦게 커밋하는 것은 성공하면 안 된다")
                .isNotNull();
        assertThat(isExpiredTransactionError(commitError))
                .as("코디네이터가 epoch를 올린 뒤이므로 펜싱/무효 상태 계열 예외를 받는다. "
                        + "정확한 타입은 클라이언트 버전과 어느 요청에서 먼저 걸리는지에 따라 달라지므로 "
                        + "원인 체인 전체를 훑어 판정한다: %s", describeChain(commitError))
                .isTrue();
        assertThat(committed)
                .as("강제 abort된 트랜잭션의 메시지는 read_committed 소비자에게 보이지 않는다")
                .isEmpty();
        assertThat(uncommitted)
                .as("메시지는 로그에 물리적으로 남아 있다 — 지워지는 게 아니라 걸러지는 것이다")
                .hasSize(1);
    }

    @Test
    @Order(2)
    @DisplayName("Q2. 아무도 개입하지 않아도 타임아웃 후 LSO가 저절로 풀린다")
    void lsoIsReleasedByCoordinatorWithoutAnyoneIntervening() throws Exception {
        KafkaProducer<String, String> abandoned = shortTimeoutProducer(TX_ID_MARKER);

        long lsoBefore;
        long hwBefore;
        long lsoAfter;
        long hwAfter;

        try {
            abandoned.initTransactions();
            abandoned.beginTransaction();
            for (int i = 1; i <= Q2_MESSAGE_COUNT; i++) {
                abandoned.send(new ProducerRecord<>(TOPIC_MARKER, "abandoned-" + i, "결말이 안 난 메시지 " + i));
            }
            abandoned.flush();

            lsoBefore = lastStableOffset(TOPIC_MARKER, 0);
            hwBefore = highWatermark(TOPIC_MARKER, 0);
            System.out.printf("  방치 직후        : LSO=%d, HW=%d, 상태=%s%n",
                    lsoBefore, hwBefore, transactionState(TX_ID_MARKER));

            assertThat(lsoBefore).as("LSO는 열린 트랜잭션의 시작 오프셋 0에 묶인다").isEqualTo(0);
            assertThat(hwBefore)
                    .as("메시지 %d건은 이미 로그에 기록됐으므로 HW는 앞서 있다", Q2_MESSAGE_COUNT)
                    .isEqualTo(Q2_MESSAGE_COUNT);

            // 여기가 Lab 01 Q2와 결정적으로 다른 지점이다.
            // Lab 01에서는 "같은 txId로 새 Producer가 initTransactions()를 호출해서" LSO가 풀렸다.
            // 여기서는 새 Producer도, abort 호출도, AdminClient 개입도 전혀 없다.
            // 오직 시간이 흐를 뿐이고, 푸는 주체는 코디네이터다.
            //
            // 중요: 이 관측을 producer.close() 이전에 끝내야 한다.
            // 트랜잭션 Producer의 close()는 진행 중인 트랜잭션을 abort하려 시도하므로,
            // 먼저 닫아버리면 "아무도 개입하지 않았다"는 전제가 깨진다.
            System.out.printf("  transaction.timeout.ms=%dms 를 넘기도록 대기한다...%n", TX_TIMEOUT_MS);
            Thread.sleep(TX_TIMEOUT_MS + 500);

            System.out.print("  아무 호출 없이 LSO 전진만 관측 ");
            lsoAfter = awaitLsoAdvance(TOPIC_MARKER, lsoBefore, ABORT_WAIT_MS);
            hwAfter = highWatermark(TOPIC_MARKER, 0);
            System.out.printf("  자동 abort 이후  : LSO=%d, HW=%d, 상태=%s%n",
                    lsoAfter, hwAfter, transactionState(TX_ID_MARKER));
        } finally {
            closeQuietly(abandoned);
        }

        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_MARKER, "lab04-marker-c-" + System.nanoTime(), 99, 4000);
        List<ConsumerRecord<String, String>> uncommitted =
                readUncommitted(TOPIC_MARKER, "lab04-marker-u-" + System.nanoTime(), Q2_MESSAGE_COUNT, 4000);
        printRecords("read_committed", committed);
        printRecords("read_uncommitted", uncommitted);
        System.out.println("  → Lab 02의 head-of-line blocking은 영원하지 않다. "
                + "최대 transaction.timeout.ms + 코디네이터 청소 주기만큼만 지속된다");
        printSeparator();

        long expectedEndOffset = Q2_MESSAGE_COUNT + 1L; // 메시지 2개 + 중단 마커 1개

        assertThat(lsoAfter)
                .as("아무도 개입하지 않았는데도 LSO가 앞으로 나갔다 — 코디네이터가 중단 마커를 썼기 때문이다")
                .isGreaterThan(lsoBefore);
        assertThat(hwAfter)
                .as("메시지 %d개 + 중단 마커(control batch) 1개 = %d", Q2_MESSAGE_COUNT, expectedEndOffset)
                .isEqualTo(expectedEndOffset);
        assertThat(lsoAfter)
                .as("결말이 안 난 트랜잭션이 더 이상 없으므로 LSO가 HW까지 따라잡는다")
                .isEqualTo(hwAfter);
        assertThat(committed)
                .as("강제 종료는 커밋이 아니라 중단이다 — 방치한 데이터가 살아나지는 않는다")
                .isEmpty();
        assertThat(uncommitted)
                .as("read_uncommitted에는 %d건이 그대로 보인다 (로그에는 남아 있다)", Q2_MESSAGE_COUNT)
                .hasSize(Q2_MESSAGE_COUNT);
    }

    @Test
    @Order(3)
    @DisplayName("Q3. 브로커의 transaction.max.timeout.ms를 넘는 값은 initTransactions()에서 거부된다")
    void timeoutLargerThanBrokerMaxIsRejected() {
        // Q1/Q2는 "너무 오래 열어두면 코디네이터가 끊는다"를 봤다.
        // 그렇다면 클라이언트가 transaction.timeout.ms를 아주 크게 잡아 그 규칙을 회피할 수 있는가?
        // 없다. 브로커가 transaction.max.timeout.ms(기본 900000ms = 15분)로 상한을 강제한다.
        KafkaProducer<String, String> greedy = null;
        Throwable rejection = null;

        try {
            // 예외가 initTransactions()가 아니라 Producer 생성 시점(설정 검증)에 날 가능성도 있으므로
            // 두 단계를 같은 try 안에 둔다.
            greedy = transactionalProducer(TX_ID_TOO_LONG, Map.of(
                    ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, String.valueOf(TOO_LONG_TIMEOUT_MS),
                    ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS));
            greedy.initTransactions();
        } catch (Throwable t) {
            rejection = t;
        } finally {
            closeQuietly(greedy);
        }

        System.out.printf("  요청한 transaction.timeout.ms = %d ms (브로커 상한 기본값 %d ms)%n",
                TOO_LONG_TIMEOUT_MS, BROKER_MAX_TX_TIMEOUT_MS);
        System.out.printf("  브로커의 응답     : %s%n", describeChain(rejection));
        System.out.println("  → 클라이언트가 트랜잭션을 무한정 붙잡아둘 수 없게 막는 안전장치다. "
                + "상한을 늘리려면 브로커 설정을 바꿔야 한다");
        printSeparator();

        assertThat(TOO_LONG_TIMEOUT_MS)
                .as("실습 전제: 요청 값이 브로커 기본 상한보다 커야 한다")
                .isGreaterThan(BROKER_MAX_TX_TIMEOUT_MS);
        assertThat(rejection)
                .as("상한을 넘는 타임아웃 요청은 조용히 잘려서 통과하는 것이 아니라 거부되어야 한다")
                .isNotNull();
        assertThat(isInvalidTimeoutError(rejection))
                .as("InvalidTxnTimeoutException이 유력하지만 클라이언트 버전에 따라 설정 검증 단계에서 "
                        + "먼저 걸릴 수도 있으므로 원인 체인 전체를 훑어 판정한다: %s", describeChain(rejection))
                .isTrue();
    }

    // ── 보조 메서드 ────────────────────────────────────────────────

    /**
     * transaction.timeout.ms를 짧게 준 트랜잭션 Producer.
     * max.block.ms도 함께 줄인다 — 펜싱된 뒤의 commitTransaction()이 기본값 60초를 다 쓰면
     * 이 Lab의 전체 소요 시간이 불필요하게 늘어난다.
     */
    private static KafkaProducer<String, String> shortTimeoutProducer(String transactionalId) {
        return transactionalProducer(transactionalId, Map.of(
                ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, String.valueOf(TX_TIMEOUT_MS),
                ProducerConfig.MAX_BLOCK_MS_CONFIG, MAX_BLOCK_MS));
    }

    /**
     * LSO가 baseline보다 앞으로 나갈 때까지 폴링한다.
     *
     * 고정 sleep을 쓰지 않는 이유: 코디네이터의 만료 처리는 타임아웃 시점에 즉시 일어나는 게 아니라
     * transaction.abort.timed.out.transaction.cleanup.interval.ms(기본 10초) 주기의
     * 백그라운드 스캔에서 일어난다. 언제 걸릴지는 스캔 주기와의 위상 차이에 달려 있어서
     * "타임아웃 + α"를 상수로 못 박으면 환경에 따라 깨진다.
     * 진행 상황을 점(.)으로 찍어 테스트가 멈춘 게 아님을 알린다.
     */
    private static long awaitLsoAdvance(String topic, long baseline, long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        long lso = lastStableOffset(topic, 0);

        while (lso <= baseline && System.currentTimeMillis() < deadline) {
            System.out.print(".");
            System.out.flush();
            Thread.sleep(1000);
            lso = lastStableOffset(topic, 0);
        }

        double elapsedSec = (System.currentTimeMillis() - start) / 1000.0;
        if (lso > baseline) {
            System.out.printf("%n  → %.1f초 만에 LSO가 %d → %d 로 전진했다%n", elapsedSec, baseline, lso);
        } else {
            System.out.printf("%n  → %.1f초를 기다렸지만 LSO가 %d에서 움직이지 않았다%n", elapsedSec, baseline);
        }
        return lso;
    }

    /** 트랜잭션 상태를 문자열로 반환한다. 조회 실패는 관찰용이므로 단정하지 않는다. */
    private static String transactionState(String transactionalId) throws Exception {
        TransactionDescription desc = describeTransaction(transactionalId);
        return desc == null ? "(조회 실패)" : desc.state().toString();
    }

    /**
     * 강제 abort된 트랜잭션을 뒤늦게 커밋할 때 나올 수 있는 예외 후보들.
     * 코디네이터가 abort하면서 epoch를 올렸으므로 펜싱 계열이 유력하지만,
     * 트랜잭션 상태 자체가 더 이상 Ongoing이 아니라는 이유로 거절될 수도 있고,
     * 클라이언트가 이미 치명적 오류 상태라 요청조차 못 보내고 타임아웃날 수도 있다.
     * 특정 타입을 콕 집지 않고 후보 집합 중 하나면 통과시킨다.
     */
    private static boolean isExpiredTransactionError(Throwable error) {
        return causedByAny(error,
                ProducerFencedException.class,
                InvalidProducerEpochException.class,
                InvalidTxnStateException.class,
                InvalidPidMappingException.class,
                UnknownProducerIdException.class,
                TimeoutException.class);
    }

    /**
     * 브로커 상한을 넘는 타임아웃 요청에 대한 예외 후보들.
     * InvalidTxnTimeoutException이 유력하지만, 클라이언트가 설정 단계에서 먼저 막으면
     * ConfigException으로, 프로토콜 검증에서 걸리면 InvalidRequestException으로 나올 수 있다.
     * 여기에 일반 TimeoutException은 넣지 않는다 — 단순 네트워크 지연이 통과해버리면
     * "거부당했다"는 명제를 검증한 게 아니게 된다.
     */
    private static boolean isInvalidTimeoutError(Throwable error) {
        return causedByAny(error,
                InvalidTxnTimeoutException.class,
                ConfigException.class,
                InvalidRequestException.class);
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
            return "(예외 없음 — 거부되지 않고 그대로 성공했다)";
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
     * 펜싱되거나 만료된 Producer는 close() 과정에서 예외를 던질 수 있는데,
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
