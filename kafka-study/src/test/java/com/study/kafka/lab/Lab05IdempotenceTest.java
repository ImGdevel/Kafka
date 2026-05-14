package com.study.kafka.lab;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 05 — enable.idempotence 동작 확인
 *
 * 검증 명제: "enable.idempotence는 무엇을 방지하는가?"
 *
 * 이 실습에서 다루는 세 가지 질문:
 *   Q1. 잘못된 설정으로 idempotence를 켜면 어떻게 되는가?
 *   Q2. idempotence는 애플리케이션 코드의 중복 전송을 막는가?
 *   Q3. idempotence는 실제로 무엇을 보장하는가?
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab05IdempotenceTest --info
 */
@Tag("lab")
@DisplayName("Lab 05 — enable.idempotence 동작 확인")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab05IdempotenceTest {

    private static final String TOPIC = "lab05-idempotence";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 05: enable.idempotence 동작 확인");
        System.out.println("  토픽: " + TOPIC + " (1파티션, RF=3)");
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 1, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC);
    }

    /**
     * Q1. 잘못된 설정 조합에서 ConfigException이 발생하는가?
     *
     * enable.idempotence=true 는 아래 조건이 반드시 충족되어야 한다.
     *   - acks=all
     *   - retries >= 1
     *   - max.in.flight.requests.per.connection <= 5
     */
    @Test
    @Order(1)
    @DisplayName("Q1: 잘못된 설정으로 idempotence를 켜면 ConfigException이 발생한다")
    void wrong_config_throws_exception() {
        System.out.println("\n  Q1: 잘못된 설정 검증");

        // acks=1 + enable.idempotence=true → 실패해야 한다
        Properties badProps = new Properties();
        badProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        badProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        badProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        badProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        badProps.put(ProducerConfig.ACKS_CONFIG, "1");       // acks=all 이어야 하는데 1을 지정
        badProps.put(ProducerConfig.RETRIES_CONFIG, "3");

        assertThatThrownBy(() -> new KafkaProducer<>(badProps))
                .isInstanceOf(Exception.class);
        System.out.println("  ✓ acks=1 + idempotence=true → 예외 발생 (올바른 동작)");

        // max.in.flight=10 + enable.idempotence=true → 실패해야 한다
        Properties badProps2 = new Properties();
        badProps2.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        badProps2.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        badProps2.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        badProps2.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        badProps2.put(ProducerConfig.ACKS_CONFIG, "all");
        badProps2.put(ProducerConfig.RETRIES_CONFIG, "3");
        badProps2.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "10"); // 5 초과

        assertThatThrownBy(() -> new KafkaProducer<>(badProps2))
                .isInstanceOf(Exception.class);
        System.out.println("  ✓ max.in.flight=10 + idempotence=true → 예외 발생 (올바른 동작)");

        System.out.println("  → idempotence는 정확한 설정 조합이 강제된다");
    }

    /**
     * Q2. 애플리케이션 코드에서 직접 중복으로 send()하면 어떻게 되는가?
     *
     * 중요한 오해:
     * enable.idempotence=true 라도 애플리케이션 코드에서 명시적으로
     * 같은 내용의 메시지를 여러 번 send()하면 모두 저장된다.
     * idempotence는 '프로토콜 레벨의 재전송 중복'만 방지한다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: idempotence는 애플리케이션 코드의 중복 전송을 막지 않는다")
    void app_level_duplicates_are_not_prevented() throws Exception {
        System.out.println("\n  Q2: 애플리케이션 레벨 중복 전송 실험");

        int sendCount = 5;
        String messageContent = "중복-메시지";
        CountDownLatch latch = new CountDownLatch(sendCount);

        // idempotence=true 로 올바르게 설정
        Properties props = idempotentProps();

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < sendCount; i++) {
                // 같은 내용을 send() 5번 → 각각 새로운 sequence number를 받는다
                producer.send(
                    new ProducerRecord<>(TOPIC, "dup-key", messageContent),
                    (meta, ex) -> latch.countDown()
                );
            }
            producer.flush();
        }

        latch.await(30, TimeUnit.SECONDS);

        // Consumer로 수신 확인
        List<ConsumerRecord<String, String>> records =
                consumeAll(TOPIC, "lab05-group-q2-" + System.currentTimeMillis(),
                        sendCount, 10_000);

        long count = records.stream()
                .filter(r -> messageContent.equals(r.value()))
                .count();

        System.out.printf("  send() 호출 횟수: %d회%n", sendCount);
        System.out.printf("  Consumer 수신 횟수: %d개%n", count);
        System.out.println();
        System.out.println("  → 중복이 " + (count == sendCount ? "발생했다 (예상된 결과)" : "발생하지 않았다"));
        System.out.println("  → idempotence는 프로토콜 재전송(네트워크 레벨)만 막는다");
        System.out.println("  → 앱 코드에서 직접 N번 send()하면 N개가 저장된다");

        // 예상: count == sendCount (5개 모두 저장됨)
        assertThat(count).as("애플리케이션 레벨 중복은 idempotence로 막을 수 없다")
                .isEqualTo(sendCount);
    }

    /**
     * Q3. idempotence가 실제로 보장하는 것은 무엇인가?
     *
     * idempotence=true 일 때:
     * - 각 메시지에 ProducerID + SequenceNumber 가 부여된다
     * - 브로커가 동일한 (ProducerID, Partition, SequenceNumber) 조합을 받으면
     *   두 번째부터 저장하지 않는다 (네트워크 재전송으로 인한 중복 방지)
     * - max.in.flight=5 까지도 순서를 보장한다
     *
     * 이 테스트는 순서 보장을 직접 확인한다.
     */
    @Test
    @Order(3)
    @DisplayName("Q3: idempotence는 max.in.flight=5에서도 순서를 보장한다")
    void idempotence_guarantees_ordering() throws Exception {
        System.out.println("\n  Q3: 순서 보장 확인 (max.in.flight=5, retries=3)");

        int msgCount = 100;
        CountDownLatch latch = new CountDownLatch(msgCount);

        Properties props = idempotentProps();
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < msgCount; i++) {
                final String seq = String.format("%04d", i);
                producer.send(
                    new ProducerRecord<>(TOPIC, "order-key", seq),
                    (meta, ex) -> latch.countDown()
                );
            }
            producer.flush();
        }

        latch.await(30, TimeUnit.SECONDS);

        List<ConsumerRecord<String, String>> records =
                consumeAll(TOPIC, "lab05-group-q3-" + System.currentTimeMillis(),
                        msgCount, 10_000);

        List<String> values = records.stream()
                .filter(r -> "order-key".equals(r.key()))
                .map(ConsumerRecord::value)
                .collect(Collectors.toList());

        // 순서가 올바른지 확인 (0000, 0001, ..., 0099 순서)
        boolean inOrder = true;
        for (int i = 0; i < values.size() - 1; i++) {
            if (values.get(i).compareTo(values.get(i + 1)) >= 0) {
                inOrder = false;
                break;
            }
        }

        System.out.printf("  전송: %d개 / 수신: %d개%n", msgCount, values.size());
        System.out.println("  순서 유지: " + (inOrder ? "✓ 유지됨" : "✗ 역전됨"));
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - idempotence=true 는 max.in.flight=5 에서도 순서를 보장한다");
        System.out.println("  - idempotence=false + retries + in-flight>1 이면 순서가 깨질 수 있다");
        System.out.println("  - 네트워크 레벨 중복 제거 + 순서 보장 = idempotent producer의 핵심");
        printSeparator();

        assertThat(inOrder).as("idempotence=true 일 때 메시지 순서가 보장되어야 한다").isTrue();
    }

    private Properties idempotentProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        // enable.idempotence=true 가 자동으로 아래 값을 강제한다:
        // acks=all, retries=Integer.MAX_VALUE, max.in.flight<=5
        return props;
    }
}
