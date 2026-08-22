package com.study.kafka.transaction;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InvalidTxnStateException;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 06 — 트랜잭션의 한계 (외부 시스템)
 *
 * 검증 명제: "Kafka 트랜잭션은 Kafka 안에서만 원자적이다"
 *
 * Lab 03에서 출력 produce와 입력 offset commit이 한 덩어리로 묶이는 것을 봤다.
 * 그게 가능했던 이유를 다시 짚어야 한다 — 오프셋 커밋도 결국 __consumer_offsets라는
 * "Kafka 토픽에 대한 쓰기"였기 때문이다. 즉 트랜잭션의 경계는 애플리케이션의 코드 블록이 아니라
 * Kafka 로그다. 코디네이터가 커밋 마커를 쓸 수 있는 범위까지가 원자성의 범위다.
 *
 * 그렇다면 외부 DB는? 코디네이터는 외부 DB에 마커를 쓸 수 없다.
 * 아무리 try 블록으로 예쁘게 감싸도 "Kafka 커밋"과 "외부 커밋"은 끝까지 별개의 두 연산이다.
 * 두 연산 사이에는 반드시 창(window)이 남고, 그 창에서 프로세스가 죽으면 한쪽만 확정된다.
 *
 * ── 외부 시스템을 스텁으로 만드는 이유 ──
 * 이 Lab의 외부 저장소는 진짜 DB가 아니라 이 클래스 안의 private 스텁(ExternalStore)이다.
 * 두 가지 이유다.
 *  1) 루트 docker-compose.yml의 Postgres(notification-producer-db / notification-worker-db)는
 *     profiles: ["notification"] 뒤에 있어 `docker compose up -d` 기본 스택에 뜨지 않는다.
 *     이 Lab 하나 때문에 별도 프로필을 띄우게 만들 이유가 없다.
 *  2) 더 중요한 이유 — 이 명제를 보이는 데 진짜 DB가 필요 없다.
 *     필요한 사실은 딱 하나, "Kafka 커밋과 외부 커밋이 별개의 두 연산"이라는 것뿐이다.
 *     JDBC를 끌어와도 증명되는 내용은 똑같고, 검증 대상만 흐려진다.
 *     (그래서 H2 / JDBC / testcontainers 같은 의존성도 일부러 추가하지 않았다.)
 *
 * Q1. Kafka를 먼저 커밋하고 외부 저장소 커밋이 실패한다.
 *     → Kafka 메시지는 이미 read_committed에 보이는데 외부에는 데이터가 없다 = 불일치.
 *     그리고 이미 커밋한 Kafka 트랜잭션은 되돌릴 수 없다는 것을 실제로 확인한다 —
 *     commitTransaction() 이후에 abortTransaction()을 부르면 되돌릴 활성 트랜잭션이 없어 예외가 난다.
 * Q2. 순서를 뒤집는다. 외부를 먼저 커밋하고 Kafka 커밋을 실패시킨다(커밋 직전 abort).
 *     → 이번엔 외부에는 있는데 Kafka에는 없다 = 반대 방향 불일치.
 *     결론: 어떤 순서로 배치해도 두 커밋 사이의 창은 사라지지 않는다. 방향만 바뀐다.
 * Q3. Outbox 패턴의 방향을 재현한다. 쓰기를 외부 저장소 한 곳으로 단일화하면
 *     원자성이 "그 저장소의 트랜잭션 하나"로 환원된다(= 분산 원자성 문제가 사라진다).
 *     대신 별도 릴레이가 outbox를 읽어 Kafka로 발행하는데 이 릴레이는 at-least-once라
 *     같은 outbox 레코드가 두 번 발행될 수 있다. 소비자가 outbox id 기준으로 멱등 처리하면
 *     최종 결과는 1건이 된다 — "원자성을 포기하는 대신 멱등성으로 막는다"가 실무의 답이다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab06*' --info
 */
@Tag("lab")
@DisplayName("Lab 06 — 트랜잭션의 한계")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab06ExternalSystemLimitTest {

    // Q별로 토픽을 완전히 분리한다. 앞 Q의 잔여 메시지가 뒤 Q의 "비어 있어야 한다" 검증을 오염시키지 않도록.
    private static final String Q1_TOPIC = "tx-lab06-q1-kafka-first";
    private static final String Q2_TOPIC = "tx-lab06-q2-external-first";
    private static final String Q3_TOPIC = "tx-lab06-q3-outbox-events";

    private static final List<String> ALL_TOPICS = List.of(Q1_TOPIC, Q2_TOPIC, Q3_TOPIC);

    private static final String Q1_TX_ID = "tx-lab06-q1-writer";
    private static final String Q2_TX_ID = "tx-lab06-q2-writer";
    private static final String Q3_TX_ID = "tx-lab06-q3-relay";

    /** Q3에서 릴레이가 발행할 outbox 레코드의 id. Kafka 메시지 키로 실려 소비자의 중복 판정 기준이 된다. */
    private static final String Q3_OUTBOX_ID = "evt-1";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 06: 트랜잭션의 한계 — 외부 시스템",
                "Kafka 트랜잭션은 Kafka 안에서만 원자적이다");
        System.out.println("  ※ 외부 시스템은 이 클래스 안의 in-memory 스텁이다. "
                + "docker-compose의 Postgres는 profiles: [\"notification\"] 뒤에 있어 기본 스택에 없고,");
        System.out.println("     이 명제를 보이는 데 진짜 DB는 필요하지 않다 — "
                + "필요한 건 '커밋이 두 개'라는 사실뿐이다.");
        // 파티션 1개 — 순서와 건수를 단순하게 관측하기 위함
        for (String topic : ALL_TOPICS) {
            createTopic(topic, 1, (short) 3);
        }
    }

    @AfterAll
    static void tearDown() {
        for (String topic : ALL_TOPICS) {
            deleteTopic(topic);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Q1. Kafka를 먼저 커밋하면, 외부 커밋이 실패해도 Kafka는 되돌릴 수 없다")
    void kafkaCommitFirstLeavesOrphanMessageWhenExternalCommitFails() {
        ExternalStore store = new ExternalStore("주문 DB");
        KafkaProducer<String, String> producer = transactionalProducer(Q1_TX_ID);

        Throwable externalError = null;
        Throwable abortError = null;

        try {
            producer.initTransactions();

            // ── 1) Kafka 트랜잭션: 여기까지는 Lab 03과 완전히 같다 ──
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(Q1_TOPIC, "order-1", "ORDER_CREATED:order-1"));
            producer.commitTransaction();
            // ↑ 이 줄이 지나는 순간 코디네이터가 커밋 마커를 썼다.
            //   Kafka 입장에서 이 트랜잭션은 "끝난 일"이고, 외부에서 무슨 일이 벌어지든 관심이 없다.
            System.out.println("  1) Kafka 커밋 완료 — 코디네이터가 커밋 마커를 썼다");

            // ── 2) 외부 저장소 커밋: 여기서 장애가 난다 ──
            //    실무에서는 DB 커넥션 끊김, 제약 조건 위반, 디스크 풀 등 무엇이든 될 수 있다.
            store.write("order-1", "주문 생성됨");
            store.failNextCommit();
            try {
                store.commit();
            } catch (ExternalCommitException e) {
                externalError = e;
            }
            System.out.printf("  2) 외부 저장소 커밋 실패 — %s%n", describeChain(externalError));

            // ── 3) 보상 시도: 이미 커밋한 Kafka 트랜잭션을 되돌려 본다 ──
            //    한쪽이 실패했으니 다른 쪽도 취소하는 게 "원자적"이라는 말의 의미다.
            //    그런데 commitTransaction() 이후의 Producer는 활성 트랜잭션이 없는 상태라
            //    abortTransaction()이 되돌릴 대상 자체가 없다 → 예외.
            //    "커밋을 취소하는 API가 없다"가 아니라 "커밋은 되돌리는 개념 자체가 없다"에 가깝다.
            try {
                producer.abortTransaction();
            } catch (Throwable t) {
                abortError = t;
            }
            System.out.printf("  3) 커밋 후 abortTransaction() 결과: %s%n", describeChain(abortError));
        } finally {
            closeQuietly(producer);
        }

        List<ConsumerRecord<String, String>> kafkaSide =
                readCommitted(Q1_TOPIC, "lab06-q1-verify-" + System.nanoTime(), 1, 5000);

        printRecords("Kafka(read_committed)", kafkaSide);
        System.out.printf("  [%-18s] %d건 %s%n", "외부 저장소", store.count(), store.snapshot());
        System.out.printf("  → Kafka=%d건, 외부=%d건 → 불일치%n", kafkaSide.size(), store.count());
        printSeparator();

        assertThat(externalError)
                .as("외부 커밋은 실패했어야 실험이 성립한다")
                .isNotNull();
        assertThat(kafkaSide)
                .as("Kafka 트랜잭션은 이미 커밋됐으므로 read_committed 소비자에게 그대로 보인다")
                .hasSize(1);
        assertThat(store.count())
                .as("외부 저장소는 커밋에 실패했으므로 아무것도 남지 않았다")
                .isZero();
        assertThat(abortError)
                .as("이미 커밋된 트랜잭션에 대한 abortTransaction()은 성공하면 안 된다")
                .isNotNull();
        assertThat(isNoOngoingTransactionError(abortError))
                .as("commitTransaction() 이후 Producer에는 되돌릴 활성 트랜잭션이 없다. "
                        + "클라이언트가 상태 전이 자체를 막으므로 IllegalStateException 계열이 유력하지만, "
                        + "정확한 타입은 클라이언트 버전에 따라 달라질 수 있어 원인 체인 전체를 훑어 판정한다: %s",
                        describeChain(abortError))
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("Q2. 순서를 뒤집어도 창은 사라지지 않는다 — 방향만 반대인 불일치가 난다")
    void externalCommitFirstLeavesOrphanRowWhenKafkaCommitFails() {
        ExternalStore store = new ExternalStore("주문 DB");
        KafkaProducer<String, String> producer = transactionalProducer(Q2_TX_ID);

        try {
            producer.initTransactions();

            // ── 1) 이번에는 외부 저장소를 먼저 커밋한다 ──
            store.write("order-2", "주문 생성됨");
            store.commit();
            System.out.printf("  1) 외부 저장소 커밋 완료 — %d건%n", store.count());

            // ── 2) Kafka 커밋이 실패한다 ──
            //    실무에서 Kafka 커밋이 실패하는 경로는 여러 가지다(코디네이터 장애, min ISR 미달,
            //    Lab 04의 타임아웃 만료, Lab 01의 좀비 펜싱 등). 여기서는 결과만 필요하므로
            //    커밋 직전 abortTransaction()으로 "Kafka 쪽만 확정되지 않은 상태"를 만든다.
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(Q2_TOPIC, "order-2", "ORDER_CREATED:order-2"));
            producer.flush(); // 브로커 로그에는 이미 기록된 상태로 만든다 — 지워지는 게 아니라 걸러지는 것임을 보이기 위해
            producer.abortTransaction();
            System.out.println("  2) Kafka 커밋 실패(abort) — read_committed에는 보이지 않게 된다");

            // ── 3) 보상 시도: 외부 저장소를 롤백해 본다 ──
            //    Q1의 Kafka와 대칭이다. 이미 커밋된 트랜잭션은 rollback()의 대상이 아니다.
            //    되돌리고 싶다면 롤백이 아니라 "취소 데이터를 새로 쓰는" 보상 트랜잭션이 필요한데,
            //    그건 원자성이 아니라 애플리케이션이 직접 짜야 하는 별도의 업무 로직이다.
            store.rollback();
            System.out.printf("  3) 외부 저장소 rollback() 호출 후에도 여전히 %d건 "
                    + "— 이미 커밋된 것은 롤백 대상이 아니다%n", store.count());
        } finally {
            closeQuietly(producer);
        }

        // 안 보여야 하는 것을 검증하므로 기대 개수를 크게 잡아 타임아웃까지 기다린다
        List<ConsumerRecord<String, String>> kafkaSide =
                readCommitted(Q2_TOPIC, "lab06-q2-verify-" + System.nanoTime(), 99, 4000);

        printRecords("Kafka(read_committed)", kafkaSide);
        System.out.printf("  [%-18s] %d건 %s%n", "외부 저장소", store.count(), store.snapshot());
        System.out.printf("  → Kafka=%d건, 외부=%d건 → Q1과 정반대 방향의 불일치%n",
                kafkaSide.size(), store.count());
        printSeparator();

        assertThat(kafkaSide)
                .as("중단된 트랜잭션의 메시지는 read_committed에 보이지 않는다")
                .isEmpty();
        assertThat(store.count())
                .as("외부 저장소는 이미 커밋됐으므로 Kafka와 무관하게 데이터가 남는다")
                .isEqualTo(1);

        // 정리 —
        // Q1: Kafka 먼저 커밋 → 외부 실패 → Kafka에만 있음 (유령 이벤트)
        // Q2: 외부 먼저 커밋 → Kafka 실패 → 외부에만 있음 (발행되지 않은 이벤트)
        // 두 커밋 사이의 창을 아무리 좁혀도 창 자체는 남는다. 순서 선택은 "어느 쪽 불일치를
        // 감당할 것인가"의 선택이지 원자성을 얻는 방법이 아니다.
        //
        // Spring의 ChainedTransactionManager(ChainedKafkaTransactionManager) 같은 도구도 마찬가지다.
        // 여러 트랜잭션 매니저의 커밋을 순서대로 호출해 창을 최대한 좁혀줄 뿐,
        // 마지막 매니저의 커밋이 실패했을 때 앞서 커밋된 매니저를 되돌리지는 못한다.
        // (그래서 spring-kafka에서도 이 클래스는 "원자성을 보장하지 않는다"는 경고와 함께 권장되지 않는다.)
        // 진짜 분산 원자성을 원하면 2PC/XA가 필요한데, Kafka 프로듀서는 XA 리소스가 아니다.
        // → 실무의 답은 원자성을 얻는 게 아니라 Q3처럼 문제를 다른 모양으로 바꾸는 것이다.
    }

    @Test
    @Order(3)
    @DisplayName("Q3. Outbox — 쓰기를 한 곳으로 모으면 원자성은 회복되지만 중복이 남는다")
    void outboxPatternTradesAtomicityForIdempotency() {
        ExternalStore store = new ExternalStore("주문 DB");
        KafkaProducer<String, String> relay = transactionalProducer(Q3_TX_ID);

        try {
            // ── 1) 업무 데이터와 발행할 메시지를 같은 외부 트랜잭션에 함께 쓴다 ──
            //    핵심은 "쓰기 대상이 한 곳뿐"이라는 것이다. 커밋이 하나면 원자성 문제도 없다.
            //    Kafka로의 발행은 이 트랜잭션에서 아예 빠진다 — 대신 outbox 레코드로 기록만 해둔다.
            store.write("order:order-3", "주문 생성됨");
            store.write("outbox:" + Q3_OUTBOX_ID, "ORDER_CREATED:order-3");
            store.commit();
            System.out.printf("  1) 외부 트랜잭션 1회로 업무 데이터 + outbox 레코드 커밋 — %s%n", store.snapshot());

            assertThat(store.count())
                    .as("커밋이 하나뿐이므로 업무 데이터와 outbox 레코드는 항상 함께 있거나 함께 없다")
                    .isEqualTo(2);

            // ── 2) 릴레이가 outbox를 읽어 Kafka로 발행한다 ──
            //    릴레이는 at-least-once다. Kafka 커밋과 "outbox 레코드를 발행 완료로 표시하는 쓰기"가
            //    또다시 별개의 두 연산이기 때문이다 — Q1/Q2에서 본 그 창이 여기로 옮겨왔을 뿐이다.
            //    Kafka는 커밋됐는데 표시를 못 남기고 릴레이가 죽으면, 재기동한 릴레이가 같은 레코드를
            //    다시 발행한다. 그 상황을 그대로 재현한다: 같은 outbox 레코드를 의도적으로 두 번 발행.
            relay.initTransactions();
            String payload = store.read("outbox:" + Q3_OUTBOX_ID);

            for (int attempt = 1; attempt <= 2; attempt++) {
                relay.beginTransaction();
                relay.send(new ProducerRecord<>(Q3_TOPIC, Q3_OUTBOX_ID, payload));
                relay.commitTransaction();
                System.out.printf("  2-%d) 릴레이가 outbox 레코드 '%s' 발행 (커밋 완료)%n", attempt, Q3_OUTBOX_ID);
            }
            System.out.println("      ↑ 2회차는 '발행 완료 표시를 남기기 전에 릴레이가 죽었다가 재기동한' 경우다");
        } finally {
            closeQuietly(relay);
        }

        // ── 3) 소비자 쪽에서 실제로 중복이 관측된다 ──
        List<ConsumerRecord<String, String>> published =
                readCommitted(Q3_TOPIC, "lab06-q3-verify-" + System.nanoTime(), 2, 6000);
        printRecords("Kafka(read_committed)", published);

        assertThat(published)
                .as("릴레이가 at-least-once이므로 같은 outbox 레코드가 두 번 보인다")
                .hasSize(2);
        assertThat(published).extracting(ConsumerRecord::key)
                .as("두 건 모두 같은 outbox id다 — 이것이 멱등 처리의 열쇠가 된다")
                .containsExactly(Q3_OUTBOX_ID, Q3_OUTBOX_ID);

        // ── 4) 소비자가 outbox id 기준으로 멱등 처리한다 ──
        //    Kafka 메시지에 outbox 레코드의 id가 실려 있으므로 소비자는 "이미 처리한 id인가"만
        //    확인하면 된다. 처리 결과 저장소에 id를 키로 쓰면 두 번 처리해도 결과는 1건이다.
        ExternalStore consumerSide = new ExternalStore("소비자 처리 결과");
        for (ConsumerRecord<String, String> record : published) {
            if (consumerSide.contains("applied:" + record.key())) {
                System.out.printf("  4) 중복 감지 — outbox id '%s'는 이미 처리했으므로 건너뛴다%n", record.key());
                continue;
            }
            consumerSide.write("applied:" + record.key(), record.value());
            consumerSide.commit();
            System.out.printf("  4) outbox id '%s' 최초 처리%n", record.key());
        }

        System.out.printf("  [%-18s] %d건 %s%n",
                "소비자 처리 결과", consumerSide.count(), consumerSide.snapshot());
        System.out.printf("  → 발행 %d건 / 최종 반영 %d건 : 원자성 대신 멱등성으로 막았다%n",
                published.size(), consumerSide.count());
        printSeparator();

        assertThat(consumerSide.count())
                .as("두 번 발행됐지만 outbox id로 중복을 걸러 최종 결과는 1건이다")
                .isEqualTo(1);

        // 정리 —
        // Outbox는 분산 원자성을 "해결"한 게 아니라 문제의 모양을 바꾼 것이다.
        //  · 쓰기를 외부 저장소 한 곳으로 모아 → 원자성 문제를 로컬 트랜잭션 하나로 환원했고
        //  · 대신 발행이 at-least-once가 되어 → 중복이라는 새 문제를 얻었으며
        //  · 그 중복은 소비자의 멱등 처리로 막는다.
        // Lab 03의 EOS가 Kafka→Kafka 경로에서 성립했던 것과 대비된다.
        // 경계를 넘는 순간 보장은 "정확히 한 번"에서 "적어도 한 번 + 멱등"으로 내려앉는다.
    }

    // ── 보조 메서드 ────────────────────────────────────────────────

    /**
     * 커밋이 끝난 Producer에 abortTransaction()을 불렀을 때 나올 수 있는 예외 후보들.
     *
     * commitTransaction()이 끝나면 클라이언트의 트랜잭션 상태는 READY로 돌아간다.
     * READY → ABORTING_TRANSACTION은 유효한 상태 전이가 아니므로 클라이언트가 요청을 보내기도 전에
     * IllegalStateException으로 막는 것이 유력한 경로다. 다만 이건 브로커 프로토콜이 아니라
     * 클라이언트 내부 상태 머신의 구현이라 버전에 따라 KafkaException으로 감싸이거나
     * 브로커까지 갔다가 InvalidTxnStateException으로 돌아올 여지도 있다.
     * 따라서 특정 타입을 콕 집지 않고 후보 집합 중 하나면 통과시킨다 (Lab 04와 같은 방식).
     */
    private static boolean isNoOngoingTransactionError(Throwable error) {
        return causedByAny(error,
                IllegalStateException.class,
                InvalidTxnStateException.class,
                KafkaException.class);
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
            return "(예외 없음 — 그대로 성공했다)";
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
     * 커밋/중단이 끝났거나 상태가 어그러진 Producer는 close() 과정에서 예외를 던질 수 있는데,
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

    // ── 외부 시스템 스텁 ───────────────────────────────────────────

    /**
     * Kafka 밖에 있는 외부 저장소를 흉내 내는 아주 단순한 in-memory 스텁.
     *
     * 진짜 DB가 아닌 이유는 클래스 Javadoc에 적어두었다. 이 스텁이 재현해야 하는 성질은 셋뿐이다.
     *  1) 쓰기는 commit() 전까지 확정되지 않는다 (pending → committed)
     *  2) commit()이 실패하면 그 트랜잭션의 쓰기는 전부 사라진다
     *  3) 이미 commit()된 데이터는 rollback()으로 되돌릴 수 없다
     * 이 셋만 있으면 "Kafka 커밋과 외부 커밋이 별개의 두 연산"이라는 명제를 관측할 수 있다.
     */
    private static final class ExternalStore {

        private final String name;
        private final Map<String, String> committed = new LinkedHashMap<>();
        private final Map<String, String> pending = new LinkedHashMap<>();
        private boolean failNextCommit;

        private ExternalStore(String name) {
            this.name = name;
        }

        /** 트랜잭션 안에서의 쓰기. 아직 확정되지 않는다. */
        void write(String key, String value) {
            pending.put(key, value);
        }

        /** 커밋 실패를 강제하는 스위치. 다음 commit() 한 번에만 적용된다. */
        void failNextCommit() {
            this.failNextCommit = true;
        }

        /** 커밋한다. 실패 스위치가 켜져 있으면 pending을 버리고 예외를 던진다(= DB가 롤백한 상황). */
        void commit() {
            if (failNextCommit) {
                failNextCommit = false;
                pending.clear();
                throw new ExternalCommitException(name + " 커밋 실패 — 이 트랜잭션의 쓰기는 사라졌다");
            }
            committed.putAll(pending);
            pending.clear();
        }

        /**
         * 롤백한다. 아직 커밋되지 않은 쓰기만 버린다.
         * 이미 커밋된 데이터에는 손대지 않는다 — 이것이 Q2에서 보이려는 대칭이다.
         */
        void rollback() {
            pending.clear();
        }

        String read(String key) {
            return committed.get(key);
        }

        boolean contains(String key) {
            return committed.containsKey(key);
        }

        int count() {
            return committed.size();
        }

        String snapshot() {
            return committed.keySet().toString();
        }
    }

    /** 외부 저장소 커밋 실패를 나타내는 예외. 실무의 SQLException 자리에 놓인다. */
    private static final class ExternalCommitException extends RuntimeException {
        private ExternalCommitException(String message) {
            super(message);
        }
    }
}
