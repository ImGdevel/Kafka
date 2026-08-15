package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.TransactionDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 01 — transactional.id와 좀비 펜싱(zombie fencing)
 *
 * 검증 명제: "같은 transactional.id를 쓰는 이전 Producer 인스턴스는 자동으로 무력화된다"
 *
 * 분산 환경에서 죽은 줄 알았던 인스턴스가 GC 정지나 네트워크 단절에서 깨어나 다시 쓰기를 시도하는 것을
 * 좀비(zombie)라고 부른다. Kafka는 transactional.id마다 producer epoch를 관리하고,
 * 같은 id로 새 Producer가 initTransactions()를 호출하면 epoch를 올려버린다.
 * 낡은 epoch를 들고 있는 좀비의 요청은 브로커가 거부한다 — 이것이 펜싱이다.
 *
 * Q1. 같은 txId로 A → B 순서로 initTransactions()를 하면, 뒤늦게 쓰려는 A가 실패하는가?
 *     (ProducerFencedException / InvalidProducerEpochException) 그리고 A의 메시지는 안 보이는가?
 * Q2. initTransactions()는 이전 인스턴스가 남긴 미완결 트랜잭션을 정리(abort)해서 LSO를 풀어주는가?
 * Q3. (대조군) transactional.id가 없는 일반 Producer 2개는 서로 펜싱되지 않는가?
 *     → 펜싱은 Producer라서 생기는 기능이 아니라 txId에 붙은 epoch의 기능이다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab01*' --info
 */
@Tag("lab")
@DisplayName("Lab 01 — 좀비 펜싱")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab01ZombieFencingTest {

    private static final String TOPIC_FENCE   = "tx-lab01-fence";
    private static final String TOPIC_DANGLING = "tx-lab01-dangling";
    private static final String TOPIC_PLAIN   = "tx-lab01-plain";

    private static final String TX_ID_FENCE    = "tx-lab01-worker";
    private static final String TX_ID_DANGLING = "tx-lab01-dangling-worker";

    private static final String VALUE_BY_ZOMBIE = "written-by-zombie-A";
    private static final String VALUE_BY_OWNER  = "written-by-owner-B";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 01: transactional.id와 좀비 펜싱",
                "같은 txId로 새 Producer가 뜨면 이전 인스턴스는 그 순간 무력화된다");
        createTopic(TOPIC_FENCE, 1, (short) 3);
        createTopic(TOPIC_DANGLING, 1, (short) 3);
        createTopic(TOPIC_PLAIN, 1, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_FENCE);
        deleteTopic(TOPIC_DANGLING);
        deleteTopic(TOPIC_PLAIN);
    }

    @Test
    @Order(1)
    @DisplayName("Q1. 같은 txId로 나중에 뜬 Producer가 이전 인스턴스를 펜싱한다")
    void olderProducerIsFencedBySameTransactionalId() throws Exception {
        KafkaProducer<String, String> zombieA = transactionalProducer(TX_ID_FENCE);
        KafkaProducer<String, String> ownerB = null;
        Throwable fencingError = null;

        try {
            // A가 먼저 txId를 점유한다
            zombieA.initTransactions();
            printEpoch("A의 initTransactions() 직후", TX_ID_FENCE);

            // B가 같은 txId로 뜬다 → 이 순간 epoch가 올라가고 A는 좀비가 된다
            ownerB = transactionalProducer(TX_ID_FENCE);
            ownerB.initTransactions();
            printEpoch("B의 initTransactions() 직후", TX_ID_FENCE);

            // B는 정상적으로 쓰고 커밋한다
            ownerB.beginTransaction();
            ownerB.send(new ProducerRecord<>(TOPIC_FENCE, "owner", VALUE_BY_OWNER));
            ownerB.commitTransaction();

            // A(좀비)가 자기가 아직 살아있는 줄 알고 뒤늦게 쓰기를 시도한다.
            // beginTransaction()은 클라이언트 로컬 동작이라 통과할 수 있고,
            // 실제 거부는 브로커와 통신하는 send()/commitTransaction() 시점에 드러난다.
            try {
                zombieA.beginTransaction();
                zombieA.send(new ProducerRecord<>(TOPIC_FENCE, "zombie", VALUE_BY_ZOMBIE)).get();
                zombieA.commitTransaction();
            } catch (Throwable t) {
                fencingError = t;
            }
        } finally {
            closeQuietly(zombieA);
            closeQuietly(ownerB);
        }

        System.out.printf("  좀비 A가 받은 예외 : %s%n", describeChain(fencingError));

        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_FENCE, "lab01-fence-" + System.nanoTime(), 99, 4000);
        printRecords("read_committed", committed);
        printSeparator();

        assertThat(fencingError)
                .as("좀비 A의 쓰기는 성공하면 안 된다")
                .isNotNull();
        assertThat(isFencingError(fencingError))
                .as("펜싱된 Producer는 ProducerFencedException 또는 InvalidProducerEpochException을 받는다 "
                        + "(실제 타입은 클라이언트 버전과 어느 요청에서 먼저 걸리는지에 따라 달라진다): %s",
                        describeChain(fencingError))
                .isTrue();

        assertThat(committed)
                .as("살아남은 것은 B의 메시지 하나뿐이다")
                .hasSize(1);
        assertThat(committed)
                .extracting(ConsumerRecord::value)
                .as("좀비 A가 보낸 메시지는 read_committed 소비자에게 절대 보이지 않는다")
                .containsExactly(VALUE_BY_OWNER);
    }

    @Test
    @Order(2)
    @DisplayName("Q2. initTransactions()는 이전 인스턴스의 미완결 트랜잭션을 정리해 LSO를 풀어준다")
    void initTransactionsResolvesDanglingTransaction() throws Exception {
        KafkaProducer<String, String> crashedA = transactionalProducer(TX_ID_DANGLING);
        KafkaProducer<String, String> recoveredB = null;

        long lsoBefore;
        long hwBefore;
        long lsoAfter;
        long hwAfter;

        try {
            // A가 begin + send + flush까지만 하고 커밋도 중단도 하지 않은 채 죽었다고 가정한다.
            // 이 상태로 두면 LSO가 묶여서 read_committed 소비자는 여기서 더 못 나간다.
            crashedA.initTransactions();
            crashedA.beginTransaction();
            crashedA.send(new ProducerRecord<>(TOPIC_DANGLING, "dangling", "never-committed"));
            crashedA.flush();

            lsoBefore = lastStableOffset(TOPIC_DANGLING, 0);
            hwBefore = highWatermark(TOPIC_DANGLING, 0);
            TransactionDescription before = describeTransaction(TX_ID_DANGLING);
            System.out.printf("  A가 방치한 직후  : LSO=%d, HW=%d, 상태=%s%n",
                    lsoBefore, hwBefore, before == null ? "(조회 실패)" : before.state().toString());

            assertThat(lsoBefore)
                    .as("미완결 트랜잭션의 시작 지점에서 LSO가 묶인다")
                    .isEqualTo(0);
            assertThat(hwBefore)
                    .as("메시지 자체는 이미 복제되었으므로 HW는 앞으로 나가 있다")
                    .isEqualTo(1);

            // 같은 txId로 B가 뜬다. initTransactions()는 epoch를 올리는 동시에
            // 이전 epoch의 진행 중 트랜잭션을 abort시키고, 그 결과가 반영될 때까지 기다린다.
            recoveredB = transactionalProducer(TX_ID_DANGLING);
            recoveredB.initTransactions();

            lsoAfter = awaitLsoAdvance(TOPIC_DANGLING, lsoBefore, 10_000);
            hwAfter = highWatermark(TOPIC_DANGLING, 0);
            TransactionDescription after = describeTransaction(TX_ID_DANGLING);
            System.out.printf("  B의 init 이후    : LSO=%d, HW=%d, 상태=%s%n",
                    lsoAfter, hwAfter, after == null ? "(조회 실패)" : after.state().toString());
            printEpoch("B의 initTransactions() 직후", TX_ID_DANGLING);
        } finally {
            closeQuietly(crashedA);
            closeQuietly(recoveredB);
        }

        // 정리된 트랜잭션은 abort로 끝났으므로 메시지는 read_committed에 보이지 않는다.
        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_DANGLING, "lab01-dangling-c-" + System.nanoTime(), 99, 4000);
        List<ConsumerRecord<String, String>> uncommitted =
                readUncommitted(TOPIC_DANGLING, "lab01-dangling-u-" + System.nanoTime(), 1, 4000);
        printRecords("read_committed", committed);
        printRecords("read_uncommitted", uncommitted);
        printSeparator();

        assertThat(lsoAfter)
                .as("새 인스턴스의 initTransactions()가 미완결 트랜잭션을 끝내주므로 LSO가 앞으로 나간다")
                .isGreaterThan(lsoBefore);
        assertThat(hwAfter)
                .as("중단 마커(control batch)가 오프셋 1칸을 차지하므로 메시지 1개 + 마커 1개 = 2")
                .isEqualTo(2);
        assertThat(lsoAfter)
                .as("더 이상 진행 중인 트랜잭션이 없으므로 LSO와 HW가 같아진다")
                .isEqualTo(hwAfter);

        assertThat(committed)
                .as("미완결 트랜잭션은 커밋이 아니라 중단으로 정리된다 — 데이터는 살아나지 않는다")
                .isEmpty();
        assertThat(uncommitted)
                .as("메시지는 로그에 물리적으로 남아 있다")
                .hasSize(1);
    }

    @Test
    @Order(3)
    @DisplayName("Q3. transactional.id가 없는 일반 Producer 2개는 서로 펜싱되지 않는다")
    void plainProducersAreNotFenced() throws Exception {
        KafkaProducer<String, String> plain1 = plainProducer();
        KafkaProducer<String, String> plain2 = plainProducer();

        try {
            RecordMetadata m1 = plain1.send(new ProducerRecord<>(TOPIC_PLAIN, "p1", "plain-1-first")).get();
            RecordMetadata m2 = plain2.send(new ProducerRecord<>(TOPIC_PLAIN, "p2", "plain-2")).get();
            // Q1에서는 바로 여기서 A가 펜싱됐다. txId가 없으면 아무 일도 일어나지 않는다.
            RecordMetadata m3 = plain1.send(new ProducerRecord<>(TOPIC_PLAIN, "p1", "plain-1-again")).get();

            System.out.printf("  Producer1 첫 쓰기 : offset=%d%n", m1.offset());
            System.out.printf("  Producer2 쓰기    : offset=%d%n", m2.offset());
            System.out.printf("  Producer1 재쓰기  : offset=%d  ← 펜싱되지 않았다%n", m3.offset());
        } finally {
            closeQuietly(plain1);
            closeQuietly(plain2);
        }

        List<ConsumerRecord<String, String>> committed =
                readCommitted(TOPIC_PLAIN, "lab01-plain-" + System.nanoTime(), 99, 4000);
        printRecords("read_committed", committed);
        printSeparator();

        assertThat(committed)
                .extracting(ConsumerRecord::value)
                .as("transactional.id가 없으면 Producer마다 별개의 PID를 받으므로 서로를 무력화하지 않는다. "
                        + "즉 펜싱은 Producer의 기능이 아니라 txId에 붙은 producer epoch의 기능이다")
                .containsExactlyInAnyOrder("plain-1-first", "plain-2", "plain-1-again");
    }

    // ── 보조 메서드 ────────────────────────────────────────────────

    /**
     * producer epoch를 출력만 한다. 단정하지 않는 이유:
     * 같은 txId의 트랜잭션 상태가 브로커에 남아 있으면 epoch가 0부터 시작하지 않고,
     * 증가 폭도 코디네이터 내부 처리에 따라 1이 아닐 수 있다. 관찰용으로만 쓴다.
     */
    private static void printEpoch(String label, String transactionalId) throws Exception {
        TransactionDescription desc = describeTransaction(transactionalId);
        if (desc == null) {
            System.out.printf("  %-28s : (조회 실패)%n", label);
            return;
        }
        System.out.printf("  %-28s : producerId=%d, producerEpoch=%d, 상태=%s%n",
                label, desc.producerId(), desc.producerEpoch(), desc.state());
    }

    /** 예외 원인 체인에 펜싱 계열 예외가 있는지 확인한다. */
    private static boolean isFencingError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProducerFencedException || cause instanceof InvalidProducerEpochException) {
                return true;
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
            return "(예외 없음 — 좀비가 그대로 성공했다)";
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
     * LSO가 baseline보다 앞으로 나갈 때까지 기다린다.
     * 중단 마커 기록과 LSO 반영 사이에 약간의 지연이 있을 수 있어서 폴링한다.
     */
    private static long awaitLsoAdvance(String topic, long baseline, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lso = lastStableOffset(topic, 0);
        while (lso <= baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            lso = lastStableOffset(topic, 0);
        }
        return lso;
    }

    /**
     * Producer를 조용히 닫는다.
     * 펜싱된 Producer는 close() 과정에서 예외를 던질 수 있는데, 그건 검증 대상이 아니라 뒷정리다.
     * close(Duration.ZERO)로 대기 없이 강제 종료하고 예외는 무시한다.
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
