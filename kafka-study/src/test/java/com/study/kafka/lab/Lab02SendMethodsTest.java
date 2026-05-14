package com.study.kafka.lab;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 02 — 전송 방식 3가지 성능 비교
 *
 * 검증 명제: "Sync 전송이 정말 Async보다 압도적으로 느린가?"
 *
 * 전송 방식:
 *   1. Fire and Forget  — send()만 호출, 결과 무시
 *   2. Synchronous      — send().get() 으로 블로킹 대기
 *   3. Asynchronous     — Callback과 함께 send(), 비동기 결과 처리
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab02SendMethodsTest --info
 */
@Tag("lab")
@DisplayName("Lab 02 — 전송 방식 3가지 성능 비교")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab02SendMethodsTest {

    private static final String TOPIC = "lab02-send-methods";
    private static final int MSG_COUNT = 500;

    private static long timeFireAndForget;
    private static long timeSync;
    private static long timeAsync;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 02: 전송 방식 3가지 성능 비교");
        System.out.println("  토픽: " + TOPIC + " (3파티션, RF=3)");
        System.out.printf("  메시지 수: %,d개 / 각 방식마다%n", MSG_COUNT);
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 3, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC);
        printComparison();
    }

    @Test
    @Order(1)
    @DisplayName("방식 1: Fire and Forget — 결과를 신경 쓰지 않는다")
    void fireAndForget() {
        long start = System.currentTimeMillis();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(baseProps())) {
            for (int i = 0; i < MSG_COUNT; i++) {
                // 반환값(Future)을 무시한다. 성공/실패 알 방법 없음.
                producer.send(new ProducerRecord<>(TOPIC, "fire-" + i));
            }
            producer.flush(); // 버퍼에 남은 것까지 전송
        }
        timeFireAndForget = System.currentTimeMillis() - start;
        printResult("Fire and Forget", MSG_COUNT, timeFireAndForget);
    }

    @Test
    @Order(2)
    @DisplayName("방식 2: Synchronous — 매 메시지마다 응답 대기")
    void synchronous() throws Exception {
        long start = System.currentTimeMillis();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(baseProps())) {
            for (int i = 0; i < MSG_COUNT; i++) {
                // .get()으로 블로킹 → 응답이 올 때까지 다음 메시지 전송 불가
                producer.send(new ProducerRecord<>(TOPIC, "sync-" + i)).get();
            }
        }
        timeSync = System.currentTimeMillis() - start;
        printResult("Synchronous (.get())", MSG_COUNT, timeSync);
    }

    @Test
    @Order(3)
    @DisplayName("방식 3: Asynchronous — Callback으로 결과 수신")
    void asynchronous() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(MSG_COUNT);
        AtomicInteger errors = new AtomicInteger(0);

        long start = System.currentTimeMillis();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(baseProps())) {
            for (int i = 0; i < MSG_COUNT; i++) {
                producer.send(
                    new ProducerRecord<>(TOPIC, "async-" + i),
                    (meta, ex) -> {
                        if (ex != null) errors.incrementAndGet();
                        latch.countDown();
                    }
                );
            }
            producer.flush();
        }
        latch.await(30, TimeUnit.SECONDS);
        timeAsync = System.currentTimeMillis() - start;

        assertThat(errors.get()).as("Async 전송 중 에러 없어야 함").isZero();
        printResult("Asynchronous (Callback)", MSG_COUNT, timeAsync);
    }

    private static void printComparison() {
        if (timeSync == 0 || timeAsync == 0 || timeFireAndForget == 0) return;

        System.out.println();
        System.out.println("  ── 비교 결과 ──────────────────────────────────────");
        System.out.printf("  Sync vs Async: %.1fx 차이%n",
                (double) timeSync / Math.max(timeAsync, 1));
        System.out.printf("  Sync vs Fire&Forget: %.1fx 차이%n",
                (double) timeSync / Math.max(timeFireAndForget, 1));
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Sync는 메시지마다 네트워크 왕복(RTT)을 기다린다");
        System.out.println("  - Async/Fire&Forget은 I/O 스레드가 전송하는 동안");
        System.out.println("    호출 스레드가 계속 다음 메시지를 보낼 수 있다");
        System.out.println("  - Fire&Forget과 Async의 차이가 작은 이유:");
        System.out.println("    → 처리량 차이보다 관리 가능성(에러 처리)이 핵심 차이");
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
