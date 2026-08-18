package com.study.kafka.transaction;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 11 — read_committed 소비 지연의 정량화
 *
 * 검증 명제: "read_committed 소비자의 지연은 트랜잭션의 '길이'에 직접 비례한다
 *            — 트랜잭션 경계가 곧 지연 예산이다"
 *
 * Lab02는 열린 트랜잭션이 LSO를 붙잡아 뒤따르는 메시지까지 막는 것을 "보인다/안 보인다"로 확인했다.
 * Lab11은 같은 현상을 밀리초로 잰다. 결론은 곧바로 실무 지침이 된다.
 * 트랜잭션을 오래 열어두는 것은 처리량 최적화이기 이전에 소비자 지연의 직접적인 원인이다.
 * (Lab07에서 본 "커밋 주기를 늘려 고정비를 상각한다"의 반대편 비용이 바로 이것이다.)
 *
 * Q1. 기준선. 메시지를 보내고 즉시 커밋했을 때 read_committed / read_uncommitted 소비자가
 *     그 메시지를 실제로 받기까지 걸린 시간을 각각 잰다. 이후 Q2/Q3 수치를 읽는 기준이 된다.
 * Q2. 핵심 실험. 트랜잭션을 열고 보낸 뒤 의도적으로 T밀리초 대기했다가 커밋한다.
 *     T를 500 / 1500 / 3000ms로 바꿔가며 read_committed 지연이 T를 따라 증가하는 것을 표로 본다.
 *     같은 시나리오에서 read_uncommitted 소비자는 T와 무관하게 즉시 받는다는 것을 나란히 보인다.
 * Q3. Lab02 Q1의 head-of-line blocking을 시간으로 잰다. Producer A가 트랜잭션을 열어 T밀리초
 *     붙잡고 있는 동안, Producer B는 즉시 커밋한다. 아무 잘못이 없는 B의 메시지가
 *     read_committed 소비자에게 도달하기까지 A의 T만큼 늦어진다 — "남의 트랜잭션이 내 지연이 된다".
 *
 * 측정 원칙 — "수치는 출력, 단정은 느슨한 관계식만":
 *   로컬 Docker 환경에서 절대 지연 시간은 디스크·컨테이너 스케줄링·JIT 상태에 따라 흔들린다.
 *   따라서 정확한 밀리초 값은 절대 assertThat으로 단정하지 않는다. 사람이 읽도록 출력만 한다.
 *   단정하는 것은 두 가지뿐이다.
 *     (1) "받았다/못 받았다" 같은 결정론적 사실 — 평소처럼 단정한다.
 *     (2) 마진을 아주 크게 잡은 관계식 — 예: T=3000ms일 때 read_committed 지연은 최소한
 *         T의 절반(1500ms)보다는 크다, read_uncommitted 지연은 T(3000ms)보다는 작다.
 *   느린 CI 머신에서도 통과하도록 부등식의 여유를 넉넉히 두었다.
 *
 * 지연 측정 방법:
 *   - Consumer를 미리 만들어 파티션에 assign하고 seekToEnd + position()으로 시작 위치를 확정한다.
 *     (seekToEnd는 lazy 평가라서 position()을 호출해야 실제 조회가 일어난다)
 *   - 그 뒤 워밍업 레코드 1건을 실제로 수신시켜 첫 poll의 메타데이터 요청·커넥션 수립·fetch 세션
 *     생성 비용이 본 측정에 섞이지 않게 한다.
 *   - 측정은 별도 스레드에서 poll 루프를 돌며 "대상 key를 가진 레코드를 실제로 받은 시각"을 재는 것이다.
 *     Thread.sleep은 측정 수단이 아니라 실험 변수(트랜잭션을 얼마나 오래 열어두는가)로만 쓴다.
 *   - subscribe 대신 assign을 써서 그룹 조인/리밸런스 지연을 측정에서 배제한다.
 *   - 기준 시각(t0)은 각 Q의 의미에 맞게 다르게 잡는다. 각 테스트 안에 주석으로 명시했다.
 *   - 시나리오마다 별도 토픽을 써서 이전 측정의 잔여 메시지가 섞이지 않게 한다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab11*' --info
 */
@Tag("lab")
@DisplayName("Lab 11 — read_committed 소비 지연 측정")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab11ConsumerLatencyTest {

    // ── 토픽 ──────────────────────────────────────────────────────
    // 모두 파티션 1개. head-of-line blocking은 "같은 파티션"에서만 재현된다.
    private static final String TOPIC_Q1 = "tx-lab11-q1-baseline";
    private static final String TOPIC_Q3 = "tx-lab11-q3-head-of-line";

    // ── transactional.id ─────────────────────────────────────────
    private static final String TX_ID_Q1 = "tx-lab11-q1-writer";
    private static final String TX_ID_Q2 = "tx-lab11-q2-writer";
    private static final String TX_ID_Q3_A = "tx-lab11-q3-holder-a";
    private static final String TX_ID_Q3_B = "tx-lab11-q3-innocent-b";

    // ── 실험 변수 ─────────────────────────────────────────────────
    /** Q2에서 트랜잭션을 열어둘 시간(ms). 전체 실행이 2분을 넘지 않도록 3개 값만 쓴다. */
    private static final int[] TX_LENGTHS_MS = {500, 1500, 3000};
    /** Q3에서 Producer A가 트랜잭션을 붙잡고 있을 시간(ms). */
    private static final int Q3_HOLD_MS = 3000;

    // ── 측정 파라미터 ─────────────────────────────────────────────
    /** poll 간격. 측정 해상도가 되므로 짧게 잡는다(수백 ms 단위 실험에는 충분). */
    private static final long POLL_MS = 50;
    /** 레코드 수신 대기 상한. 가장 긴 실험 변수(3000ms)보다 훨씬 크게 잡는다. */
    private static final long PROBE_TIMEOUT_MS = 25_000;
    /** 워밍업 레코드 수신 대기 상한. */
    private static final long WARMUP_TIMEOUT_MS = 10_000;
    /** 워밍업 레코드의 key. 측정 대상 key와 절대 겹치지 않게 한다. */
    private static final String WARMUP_KEY = "lab11-warmup";
    /** 수신하지 못했음을 나타내는 값. */
    private static final long NOT_RECEIVED = -1L;

    /** 두 소비자(read_committed / read_uncommitted)를 동시에 폴링하기 위한 스레드 풀. */
    private static ExecutorService pool;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 11: read_committed 소비 지연 측정",
                "트랜잭션을 여는 순간부터 닫을 때까지가 곧 read_committed 소비자의 지연이다");

        createTopic(TOPIC_Q1, 1, (short) 3);
        for (int t : TX_LENGTHS_MS) {
            // T값마다 별도 토픽 — 앞선 측정의 잔여 메시지/마커가 다음 측정에 섞이지 않게 한다.
            createTopic(q2Topic(t), 1, (short) 3);
        }
        createTopic(TOPIC_Q3, 1, (short) 3);

        pool = Executors.newFixedThreadPool(2);

        System.out.println("  준비 완료 — 수치는 출력만 하고, 단정은 마진을 크게 둔 관계식만 사용한다");
        printSeparator();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        deleteTopic(TOPIC_Q1);
        for (int t : TX_LENGTHS_MS) {
            deleteTopic(q2Topic(t));
        }
        deleteTopic(TOPIC_Q3);
        printSummary();
    }

    // ─────────────────────────────────────────────────────────────
    // Q1 — 기준선
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Q1. 기준선 — 즉시 커밋했을 때의 read_committed / read_uncommitted 소비 지연")
    void baselineLatencyWithImmediateCommit() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC_Q1, 0);
        String key = "q1-probe";

        long committedLatency;
        long uncommittedLatency;

        try (KafkaProducer<String, String> producer = transactionalProducer(TX_ID_Q1);
             KafkaConsumer<String, String> rc = consumer("lab11-q1-c-" + System.nanoTime(), "read_committed");
             KafkaConsumer<String, String> ru = consumer("lab11-q1-u-" + System.nanoTime(), "read_uncommitted")) {

            // initTransactions()는 코디네이터 탐색 + PID 발급이다. 1회성 시작 비용이므로
            // 측정 구간(t0 이후)에 들어가지 않도록 미리 끝내둔다.
            producer.initTransactions();

            assignAtEnd(rc, tp);
            assignAtEnd(ru, tp);
            warmUpProbes(TOPIC_Q1, rc, ru);

            Future<Long> committedProbe = probeAsync(rc, key);
            Future<Long> uncommittedProbe = probeAsync(ru, key);

            producer.beginTransaction();
            // 기준 시각(t0): send 직전.
            // Q1은 "보내고 곧바로 커밋"이므로 send 시점부터 재면 전송 + 커밋 + 전파에 드는
            // 순수 왕복 비용이 그대로 나온다. 이것이 이후 Q2/Q3 수치에서 빼고 봐야 할 바탕값이다.
            long t0 = System.nanoTime();
            producer.send(new ProducerRecord<>(TOPIC_Q1, key, "즉시 커밋"));
            producer.commitTransaction();

            committedLatency = latencyMs(committedProbe, t0);
            uncommittedLatency = latencyMs(uncommittedProbe, t0);
        }

        printLatency("Q1 read_committed", committedLatency);
        printLatency("Q1 read_uncommitted", uncommittedLatency);
        System.out.println("  → 트랜잭션을 곧바로 닫으면 두 격리 수준의 지연 차이는 커밋 왕복 한 번 정도다.");
        System.out.println("     즉 read_committed 자체가 느린 것이 아니다. 느리게 만드는 것은 '열린 시간'이다.");
        printSeparator();

        // 단정은 결정론적 사실만. 절대 지연 값은 단정하지 않는다.
        assertThat(committedLatency)
                .as("커밋을 끝냈으므로 read_committed 소비자는 반드시 메시지를 받는다")
                .isNotEqualTo(NOT_RECEIVED);
        assertThat(uncommittedLatency)
                .as("read_uncommitted 소비자도 당연히 메시지를 받는다")
                .isNotEqualTo(NOT_RECEIVED);
    }

    // ─────────────────────────────────────────────────────────────
    // Q2 — 핵심 실험
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Q2. 트랜잭션을 T밀리초 열어두면 read_committed 지연도 T만큼 늘어난다")
    void committedLatencyGrowsWithTransactionLength() throws Exception {
        long[] committedLatencies = new long[TX_LENGTHS_MS.length];
        long[] uncommittedLatencies = new long[TX_LENGTHS_MS.length];

        try (KafkaProducer<String, String> producer = transactionalProducer(TX_ID_Q2)) {
            producer.initTransactions();

            for (int i = 0; i < TX_LENGTHS_MS.length; i++) {
                int txLengthMs = TX_LENGTHS_MS[i];
                String topic = q2Topic(txLengthMs);
                TopicPartition tp = new TopicPartition(topic, 0);
                String key = "q2-t" + txLengthMs;

                try (KafkaConsumer<String, String> rc =
                             consumer("lab11-q2-c-" + System.nanoTime(), "read_committed");
                     KafkaConsumer<String, String> ru =
                             consumer("lab11-q2-u-" + System.nanoTime(), "read_uncommitted")) {

                    assignAtEnd(rc, tp);
                    assignAtEnd(ru, tp);
                    warmUpProbes(topic, rc, ru);

                    Future<Long> committedProbe = probeAsync(rc, key);
                    Future<Long> uncommittedProbe = probeAsync(ru, key);

                    producer.beginTransaction();
                    // 기준 시각(t0): send 직전.
                    // Q2에서 "커밋 지연이 곧 소비 지연"이라는 것을 드러내려면 반드시 send 기준이어야 한다.
                    // 커밋 시점 기준으로 재면 T가 측정에서 사라져 실험 자체가 무의미해진다.
                    long t0 = System.nanoTime();
                    producer.send(new ProducerRecord<>(topic, key, "T=" + txLengthMs + "ms 후 커밋"));
                    // 브로커 로그에는 이미 기록된 상태로 만든다.
                    // 이래야 read_uncommitted 소비자가 "커밋과 무관하게" 즉시 볼 수 있다.
                    producer.flush();

                    // 이 sleep은 측정 수단이 아니라 실험 변수다 — 트랜잭션을 얼마나 오래 열어두는가.
                    // 실제 지연은 아래 probe가 poll로 받은 시각으로 잰다.
                    Thread.sleep(txLengthMs);

                    producer.commitTransaction();

                    committedLatencies[i] = latencyMs(committedProbe, t0);
                    uncommittedLatencies[i] = latencyMs(uncommittedProbe, t0);
                }
            }
        }

        // 표로 출력 — 라운드별 로그에 섞이지 않도록 측정이 모두 끝난 뒤 한 번에 그린다.
        System.out.println("  트랜잭션 길이 T | read_committed 지연 | read_uncommitted 지연");
        System.out.println("  " + "-".repeat(60));
        for (int i = 0; i < TX_LENGTHS_MS.length; i++) {
            System.out.printf("  %10d ms | %14s | %16s%n",
                    TX_LENGTHS_MS[i],
                    formatLatency(committedLatencies[i]),
                    formatLatency(uncommittedLatencies[i]));
        }
        System.out.println("  " + "-".repeat(60));
        System.out.println("  → read_committed 지연은 T를 따라 통째로 밀린다. 커밋이 늦으면 소비도 그만큼 늦다.");
        System.out.println("     read_uncommitted 지연은 T와 무관하다 — 기다리게 만드는 것은 커밋뿐이다.");
        printSeparator();

        int last = TX_LENGTHS_MS.length - 1;
        int longestT = TX_LENGTHS_MS[last];

        for (int i = 0; i < TX_LENGTHS_MS.length; i++) {
            assertThat(committedLatencies[i])
                    .as("T=%dms: 커밋을 끝냈으므로 read_committed 소비자는 결국 받는다", TX_LENGTHS_MS[i])
                    .isNotEqualTo(NOT_RECEIVED);
            assertThat(uncommittedLatencies[i])
                    .as("T=%dms: read_uncommitted 소비자는 커밋을 기다리지 않고 받는다", TX_LENGTHS_MS[i])
                    .isNotEqualTo(NOT_RECEIVED);

            // 마진 50%: 커밋은 sleep(T) '뒤'에 일어나므로 실제 지연은 구조상 T 이상이다.
            // 절반만 넘으면 통과시켜 느린 머신에서도 안전하게 한다.
            assertThat(committedLatencies[i])
                    .as("T=%dms 동안 트랜잭션이 열려 있었으므로 read_committed 지연은 최소 T의 절반(%dms)보다 크다",
                            TX_LENGTHS_MS[i], TX_LENGTHS_MS[i] / 2)
                    .isGreaterThan(TX_LENGTHS_MS[i] / 2L);
        }

        // 가장 긴 T에서만 read_uncommitted 쪽을 단정한다. 마진이 3000ms로 가장 크기 때문이다.
        // (T=500ms에서 "500ms 미만"을 요구하면 느린 머신에서 아슬아슬해진다)
        assertThat(uncommittedLatencies[last])
                .as("read_uncommitted 소비자는 커밋을 기다리지 않으므로 T(%dms)보다 훨씬 빨리 받는다", longestT)
                .isLessThan(longestT);

        // 마진 50%: T가 500 → 3000ms로 2500ms 늘었으니 지연도 최소 그 절반(1250ms)만큼은 늘어야 한다.
        long expectedIncrease = (longestT - TX_LENGTHS_MS[0]) / 2L;
        assertThat(committedLatencies[last] - committedLatencies[0])
                .as("T가 %dms → %dms로 늘면 read_committed 지연도 최소 %dms 이상 함께 늘어난다",
                        TX_LENGTHS_MS[0], longestT, expectedIncrease)
                .isGreaterThan(expectedIncrease);
    }

    // ─────────────────────────────────────────────────────────────
    // Q3 — 남의 트랜잭션이 내 지연이 된다
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Q3. 즉시 커밋한 B의 메시지가, 남이 붙잡은 트랜잭션 A 때문에 늦게 도착한다")
    void headOfLineBlockingMeasuredInMillis() throws Exception {
        TopicPartition tp = new TopicPartition(TOPIC_Q3, 0);
        String keyA = "q3-holder-a";
        String keyB = "q3-innocent-b";

        long committedLatencyB;
        long uncommittedLatencyB;

        try (KafkaProducer<String, String> producerA = transactionalProducer(TX_ID_Q3_A);
             KafkaProducer<String, String> producerB = transactionalProducer(TX_ID_Q3_B);
             KafkaConsumer<String, String> rc = consumer("lab11-q3-c-" + System.nanoTime(), "read_committed");
             KafkaConsumer<String, String> ru = consumer("lab11-q3-u-" + System.nanoTime(), "read_uncommitted")) {

            producerA.initTransactions();
            producerB.initTransactions();

            assignAtEnd(rc, tp);
            assignAtEnd(ru, tp);
            warmUpProbes(TOPIC_Q3, rc, ru);

            // A: 트랜잭션을 열고 보내기만 한 뒤 붙잡는다. 이 순간 LSO가 A의 시작 오프셋에 묶인다.
            producerA.beginTransaction();
            producerA.send(new ProducerRecord<>(TOPIC_Q3, keyA, "A가 트랜잭션을 붙잡는다"));
            producerA.flush();

            // 측정 대상은 A가 아니라 B다. A의 레코드는 key로 걸러진다.
            Future<Long> committedProbe = probeAsync(rc, keyB);
            Future<Long> uncommittedProbe = probeAsync(ru, keyB);

            // B: A와 아무 관계없는 별개 트랜잭션. 보내자마자 커밋을 끝낸다.
            producerB.beginTransaction();
            // 기준 시각(t0): B의 send 직전.
            // B는 곧바로 커밋했으므로 이 시각 이후의 지연은 B 자신의 책임이 아니다.
            // 여기서 나오는 수치가 통째로 "A가 붙잡은 시간"이라는 것이 Q3의 요지다.
            long t0 = System.nanoTime();
            producerB.send(new ProducerRecord<>(TOPIC_Q3, keyB, "B는 즉시 커밋했다"));
            producerB.commitTransaction();

            // A가 계속 붙잡고 있는 시간. 실험 변수이지 측정 수단이 아니다.
            Thread.sleep(Q3_HOLD_MS);

            // A를 커밋해 막힌 것을 푼다. 열어둔 트랜잭션을 남기면 이후 실습이 LSO에 막힌다.
            producerA.commitTransaction();

            committedLatencyB = latencyMs(committedProbe, t0);
            uncommittedLatencyB = latencyMs(uncommittedProbe, t0);
        }

        System.out.printf("  A가 트랜잭션을 붙잡은 시간: %d ms (B는 그 사이 즉시 커밋)%n", Q3_HOLD_MS);
        printLatency("Q3 B의 read_committed", committedLatencyB);
        printLatency("Q3 B의 read_uncommitted", uncommittedLatencyB);
        System.out.println("  → B는 아무 잘못이 없다. 그런데도 A가 붙잡은 시간만큼 통째로 늦게 도착했다.");
        System.out.println("     같은 파티션을 쓰는 이상, 남의 트랜잭션 길이가 곧 내 소비 지연이 된다.");
        printSeparator();

        assertThat(committedLatencyB)
                .as("A가 커밋을 끝낸 뒤에는 B의 메시지도 결국 read_committed 소비자에게 도달한다")
                .isNotEqualTo(NOT_RECEIVED);
        assertThat(uncommittedLatencyB)
                .as("read_uncommitted 소비자는 LSO에 막히지 않으므로 B의 메시지를 받는다")
                .isNotEqualTo(NOT_RECEIVED);

        // 마진 50%: A는 B의 커밋 '뒤에' Q3_HOLD_MS만큼 더 붙잡으므로 구조상 지연은 그 이상이다.
        assertThat(committedLatencyB)
                .as("B의 read_committed 지연은 A가 붙잡은 시간의 최소 절반(%dms)보다 크다 — 원인은 전적으로 A다",
                        Q3_HOLD_MS / 2)
                .isGreaterThan(Q3_HOLD_MS / 2L);
        assertThat(uncommittedLatencyB)
                .as("read_uncommitted 소비자는 A의 트랜잭션과 무관하게 B를 %dms 안에 받는다", Q3_HOLD_MS)
                .isLessThan(Q3_HOLD_MS);
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼 (TxHelper에는 지연 측정용 헬퍼가 없어 이 클래스에만 둔다)
    // ─────────────────────────────────────────────────────────────

    private static String q2Topic(int txLengthMs) {
        return "tx-lab11-q2-t" + txLengthMs;
    }

    /**
     * 파티션을 assign하고 시작 위치를 로그의 끝으로 확정한다.
     * seekToEnd는 lazy 평가라 position()을 호출해야 실제 오프셋 조회가 일어난다.
     * 이 조회 비용이 첫 측정에 섞이지 않게 하려고 측정 전에 미리 끝내둔다.
     */
    private static void assignAtEnd(KafkaConsumer<String, String> consumer, TopicPartition tp) {
        consumer.assign(List.of(tp));
        consumer.seekToEnd(List.of(tp));
        consumer.position(tp); // lazy 평가 강제 종료
    }

    /**
     * 두 소비자를 워밍업한다.
     * 방식: 일반(비트랜잭션) Producer로 워밍업 레코드 1건을 보내고, 두 소비자가 그것을 실제로
     * 수신할 때까지 poll한다. 비트랜잭션 레코드는 격리 수준과 무관하게 양쪽 모두에게 보인다.
     * 목적: 첫 poll에 딸려오는 메타데이터 요청·커넥션 수립·fetch 세션 생성 비용을 측정에서 제외한다.
     * 워밍업 레코드를 소비하고 나면 두 소비자의 position은 그대로 로그의 끝이므로 다시 seek하지 않는다.
     */
    private static void warmUpProbes(String topic,
                                     KafkaConsumer<String, String> committedConsumer,
                                     KafkaConsumer<String, String> uncommittedConsumer) {
        try (KafkaProducer<String, String> warm = plainProducer()) {
            warm.send(new ProducerRecord<>(topic, WARMUP_KEY, "warmup"));
            warm.flush();
        }
        awaitKey(committedConsumer, WARMUP_KEY, WARMUP_TIMEOUT_MS);
        awaitKey(uncommittedConsumer, WARMUP_KEY, WARMUP_TIMEOUT_MS);
    }

    /**
     * 별도 스레드에서 poll 루프를 돌며 대상 key의 레코드를 처음 받은 시각(nanoTime)을 구한다.
     * read_committed와 read_uncommitted를 '같은 시간축에서' 나란히 재려면 동시에 폴링해야 하므로
     * 소비자마다 스레드를 하나씩 쓴다. KafkaConsumer는 스레드 안전하지 않지만, 소비자 하나를
     * 동시에 건드리는 스레드는 없다(main → pool 스레드로 순차 인계된다).
     */
    private static Future<Long> probeAsync(KafkaConsumer<String, String> consumer, String targetKey) {
        Callable<Long> task = () -> awaitKey(consumer, targetKey, PROBE_TIMEOUT_MS);
        return pool.submit(task);
    }

    /**
     * targetKey를 가진 레코드를 받을 때까지 poll하고, 받은 시각(nanoTime)을 반환한다.
     * timeoutMs 안에 못 받으면 NOT_RECEIVED를 반환한다.
     * 시각은 poll이 반환한 직후에 찍으므로 측정 해상도는 POLL_MS 수준이다.
     */
    private static long awaitKey(KafkaConsumer<String, String> consumer, String targetKey, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(POLL_MS));
            long receivedAt = System.nanoTime();
            for (ConsumerRecord<String, String> record : records) {
                if (targetKey.equals(record.key())) {
                    return receivedAt;
                }
            }
        }
        return NOT_RECEIVED;
    }

    /** 수신 시각과 기준 시각의 차이를 밀리초로 환산한다. 못 받았으면 NOT_RECEIVED. */
    private static long latencyMs(Future<Long> probe, long baselineNano) throws Exception {
        long receivedAt = probe.get();
        if (receivedAt == NOT_RECEIVED) {
            return NOT_RECEIVED;
        }
        return (receivedAt - baselineNano) / 1_000_000L;
    }

    private static String formatLatency(long latencyMs) {
        return latencyMs == NOT_RECEIVED ? "수신 실패" : String.format("%,d ms", latencyMs);
    }

    /** 지연 수치를 저장소의 기존 측정 출력 관례에 맞춰 한 줄로 출력한다. 어디까지나 '출력'이다. */
    private static void printLatency(String label, long latencyMs) {
        System.out.printf("  [%-25s] %s%n", label, formatLatency(latencyMs));
    }

    /** 마지막 요약. 측정치는 환경에 따라 흔들리므로 지침만 남긴다. */
    private static void printSummary() {
        System.out.println();
        System.out.println("  ── Lab 11 요약 ────────────────────────────────────");
        System.out.println("  - read_committed 소비자의 지연 ≒ (트랜잭션이 열려 있던 시간) + (커밋 왕복 시간)");
        System.out.println("  - 그 시간은 내가 연 트랜잭션이든 남이 연 트랜잭션이든 똑같이 부과된다(Q3).");
        System.out.println("  - Lab07의 '커밋 주기를 늘려 고정비를 상각한다'는 이 지연과 정면으로 맞바꾸는 선택이다.");
        System.out.println("  - 실무 지침: 트랜잭션을 여는 순간부터 닫을 때까지가 곧 소비자 지연이다.");
        System.out.println("    트랜잭션 경계를 정할 때는 '얼마나 묶을까'가 아니라 '지연을 얼마나 허용할까'로 정한다.");
        System.out.println("  * 위 지연 수치는 로컬 환경 참고치이며, 단정에는 마진을 크게 둔 관계식만 사용했다.");
        printSeparator();
    }
}
