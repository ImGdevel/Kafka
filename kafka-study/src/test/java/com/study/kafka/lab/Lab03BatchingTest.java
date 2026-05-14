package com.study.kafka.lab;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 03 — linger.ms 설정이 배치 효율에 미치는 영향
 *
 * 검증 명제: "linger.ms를 주면 정말 처리량이 올라가는가?"
 *
 * 측정 지표:
 *   - 총 소요 시간 (ms)
 *   - KafkaProducer 내장 메트릭: request-rate (초당 요청 수)
 *   - record-send-rate (초당 메시지 수)
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab03BatchingTest --info
 */
@Tag("lab")
@DisplayName("Lab 03 — linger.ms 설정과 배치 효율")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab03BatchingTest {

    private static final String TOPIC = "lab03-batching";
    private static final int MSG_COUNT = 5000;

    private static long timeNoLinger;
    private static double requestRateNoLinger;
    private static long timeLinger;
    private static double requestRateLinger;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 03: linger.ms 설정과 배치 효율");
        System.out.println("  토픽: " + TOPIC + " (1파티션, RF=3)");
        System.out.printf("  메시지 수: %,d개 / 각 설정마다%n", MSG_COUNT);
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 1, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC);
        printComparison();
    }

    @Test
    @Order(1)
    @DisplayName("linger.ms=0: 즉시 전송 (기본값)")
    void noLinger() throws InterruptedException {
        Properties props = baseProps();
        props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        // batch.size를 작게 설정해 배치 묶음 효과를 최소화
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "200");

        Map<MetricName, ? extends Metric> metrics;
        CountDownLatch latch = new CountDownLatch(MSG_COUNT);

        long start = System.currentTimeMillis();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < MSG_COUNT; i++) {
                producer.send(
                    new ProducerRecord<>(TOPIC, "msg-" + i),
                    (meta, ex) -> latch.countDown()
                );
            }
            producer.flush();
            metrics = producer.metrics();
        }
        timeNoLinger = System.currentTimeMillis() - start;
        requestRateNoLinger = getMetric(metrics, "request-rate");

        boolean done = latch.await(30, TimeUnit.SECONDS);
        assertThat(done).isTrue();

        System.out.printf("  [linger.ms=0 ] 소요: %4dms | request-rate: %.1f req/s%n",
                timeNoLinger, requestRateNoLinger);
    }

    @Test
    @Order(2)
    @DisplayName("linger.ms=50: 배치 대기 후 전송")
    void withLinger() throws InterruptedException {
        Properties props = baseProps();
        props.put(ProducerConfig.LINGER_MS_CONFIG, "50");
        // 배치 크기는 크게 — linger 시간 동안 더 많이 모임
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "65536");

        Map<MetricName, ? extends Metric> metrics;
        CountDownLatch latch = new CountDownLatch(MSG_COUNT);

        long start = System.currentTimeMillis();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < MSG_COUNT; i++) {
                producer.send(
                    new ProducerRecord<>(TOPIC, "msg-" + i),
                    (meta, ex) -> latch.countDown()
                );
            }
            producer.flush();
            metrics = producer.metrics();
        }
        timeLinger = System.currentTimeMillis() - start;
        requestRateLinger = getMetric(metrics, "request-rate");

        boolean done = latch.await(30, TimeUnit.SECONDS);
        assertThat(done).isTrue();

        System.out.printf("  [linger.ms=50] 소요: %4dms | request-rate: %.1f req/s%n",
                timeLinger, requestRateLinger);
    }

    private static void printComparison() {
        if (timeNoLinger == 0 || timeLinger == 0) return;

        System.out.println();
        System.out.println("  ── 비교 결과 ──────────────────────────────────────");
        System.out.printf("  request-rate 감소율: %.0f%%  (linger가 클수록 요청 횟수 줄어듦)%n",
                (1.0 - requestRateLinger / Math.max(requestRateNoLinger, 0.001)) * 100);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - linger.ms=0: 메시지가 들어오는 즉시 전송 → 요청 횟수 많음");
        System.out.println("  - linger.ms=50: 50ms 동안 메시지를 모아 한 번에 전송 → 요청 횟수 적음");
        System.out.println("  - 요청 횟수가 줄면 브로커 부하가 낮아지고 전체 처리량이 올라간다");
        System.out.println("  - 단, 메시지 하나당 지연은 최대 50ms 증가할 수 있다 (트레이드오프)");
        printSeparator();
    }

    private Properties baseProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        return props;
    }
}
