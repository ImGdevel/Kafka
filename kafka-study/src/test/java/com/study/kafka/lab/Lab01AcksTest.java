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
 * Lab 01 — acks 설정별 처리량 비교
 *
 * 검증 명제: "acks=0이 정말 더 빠른가? acks=all은 얼마나 느린가?"
 *
 * 실행 전 조건:
 *   docker-compose up -d
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab01AcksTest --info
 */
@Tag("lab")
@DisplayName("Lab 01 — acks 설정별 처리량 비교")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab01AcksTest {

    private static final String TOPIC = "lab01-acks";
    private static final int MSG_COUNT = 1000;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 01: acks 설정별 처리량 비교");
        System.out.println("  토픽: " + TOPIC + " (3파티션, RF=3, min.insync.replicas=2)");
        System.out.printf("  메시지 수: %,d개 / 각 설정마다%n", MSG_COUNT);
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 3, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC);
    }

    @Test
    @Order(1)
    @DisplayName("acks=0: 브로커 응답 없이 전송 (유실 가능)")
    void acks_zero() throws Exception {
        Properties props = baseProps();
        props.put(ProducerConfig.ACKS_CONFIG, "0");
        long elapsed = sendAndMeasure(props);
        printResult("acks=0", MSG_COUNT, elapsed);
    }

    @Test
    @Order(2)
    @DisplayName("acks=1: 리더 레플리카만 확인")
    void acks_one() throws Exception {
        Properties props = baseProps();
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        long elapsed = sendAndMeasure(props);
        printResult("acks=1", MSG_COUNT, elapsed);
    }

    @Test
    @Order(3)
    @DisplayName("acks=all: 모든 ISR 확인 (가장 안전)")
    void acks_all() throws Exception {
        Properties props = baseProps();
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        long elapsed = sendAndMeasure(props);
        printResult("acks=all", MSG_COUNT, elapsed);

        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - acks=0 이 가장 빠르다 (응답을 아예 기다리지 않음)");
        System.out.println("  - acks=all 이 가장 느리다 (ISR 2개 이상이 받아야 응답)");
        System.out.println("  - 단, 종단 지연(consumer가 읽을 수 있을 때까지)은 셋 다 동일하다");
        System.out.println("    → 카프카는 모든 ISR 복제 완료 후에만 consumer가 읽을 수 있기 때문");
        printSeparator();
    }

    /**
     * 프로듀서를 생성하고 MSG_COUNT개 메시지를 비동기 전송한다.
     * 모든 콜백이 완료될 때까지 기다린 후 소요 시간(ms)을 반환한다.
     */
    private long sendAndMeasure(Properties props) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(MSG_COUNT);
        AtomicInteger errors = new AtomicInteger(0);

        long start = System.currentTimeMillis();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < MSG_COUNT; i++) {
                final int seq = i;
                producer.send(
                    new ProducerRecord<>(TOPIC, String.valueOf(seq % 3), "msg-" + seq),
                    (meta, ex) -> {
                        if (ex != null) errors.incrementAndGet();
                        latch.countDown();
                    }
                );
            }
            producer.flush();
        }

        boolean done = latch.await(30, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(done).as("30초 내에 모든 메시지가 완료되어야 한다").isTrue();
        assertThat(errors.get()).as("에러가 없어야 한다").isZero();
        return elapsed;
    }

    private Properties baseProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.LINGER_MS_CONFIG, "0");         // 배치 효과 제거 (순수 acks 비교용)
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "1");        // 배치 최소화
        props.put(ProducerConfig.RETRIES_CONFIG, "0");           // 재시도 없음 (순수 속도 측정)
        return props;
    }
}
