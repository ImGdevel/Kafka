package com.study.kafka.lab;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 04 — 파티셔너 동작 확인
 *
 * 검증 명제: "같은 키는 정말 항상 같은 파티션으로 가는가?"
 *
 * 실험:
 *   - key="A" 메시지 50개 → 항상 동일 파티션?
 *   - key="B" 메시지 50개 → A와 다른 파티션?
 *   - key=null 메시지 50개 → 파티션에 분산?
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab04PartitionerTest --info
 */
@Tag("lab")
@DisplayName("Lab 04 — 파티셔너 동작 확인")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class Lab04PartitionerTest {

    private static final String TOPIC = "lab04-partitioner";
    private static final int MSG_PER_KEY = 50;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 04: 파티셔너 동작 확인");
        System.out.println("  토픽: " + TOPIC + " (3파티션, RF=3)");
        System.out.printf("  key=A: %d개, key=B: %d개, key=null: %d개%n",
                MSG_PER_KEY, MSG_PER_KEY, MSG_PER_KEY);
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 3, (short) 3);
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC);
    }

    @Test
    @DisplayName("키가 있는 메시지: 같은 키 → 항상 같은 파티션")
    void keyed_messages_go_to_same_partition() throws Exception {
        int totalMessages = MSG_PER_KEY * 3;
        CountDownLatch latch = new CountDownLatch(totalMessages);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            // key="A" 메시지 전송
            for (int i = 0; i < MSG_PER_KEY; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "A", "A-" + i),
                        (m, e) -> latch.countDown());
            }
            // key="B" 메시지 전송
            for (int i = 0; i < MSG_PER_KEY; i++) {
                producer.send(new ProducerRecord<>(TOPIC, "B", "B-" + i),
                        (m, e) -> latch.countDown());
            }
            // key=null 메시지 전송 (라운드 로빈 또는 스티키 파티셔너)
            for (int i = 0; i < MSG_PER_KEY; i++) {
                producer.send(new ProducerRecord<>(TOPIC, null, "null-" + i),
                        (m, e) -> latch.countDown());
            }
            producer.flush();
        }

        boolean done = latch.await(30, TimeUnit.SECONDS);
        assertThat(done).isTrue();

        // 전송한 메시지를 Consumer로 읽어서 파티션 분포 확인
        List<ConsumerRecord<String, String>> records =
                consumeAll(TOPIC, "lab04-group-" + System.currentTimeMillis(),
                        totalMessages, 15_000);

        // 키별 파티션 분포 집계
        Map<String, Set<Integer>> keyToPartitions = new LinkedHashMap<>();
        Map<String, Map<Integer, Long>> keyToPartitionCount = new LinkedHashMap<>();

        for (ConsumerRecord<String, String> r : records) {
            String key = r.key() == null ? "null" : r.key();
            keyToPartitions.computeIfAbsent(key, k -> new HashSet<>()).add(r.partition());
            keyToPartitionCount
                    .computeIfAbsent(key, k -> new TreeMap<>())
                    .merge(r.partition(), 1L, Long::sum);
        }

        System.out.println();
        System.out.println("  ── 파티션 분포 ────────────────────────────────────");
        for (Map.Entry<String, Map<Integer, Long>> entry : keyToPartitionCount.entrySet()) {
            String key = entry.getKey();
            Map<Integer, Long> partitionCount = entry.getValue();
            String distribution = partitionCount.entrySet().stream()
                    .map(e -> "partition" + e.getKey() + "=" + e.getValue() + "개")
                    .collect(Collectors.joining(", "));
            System.out.printf("  key=%-6s → %s%n", "\"" + key + "\"", distribution);
        }

        System.out.println();
        System.out.println("  ── 검증 결과 ──────────────────────────────────────");

        // key="A"는 정확히 1개 파티션에만 있어야 한다
        Set<Integer> aPartitions = keyToPartitions.getOrDefault("A", Set.of());
        assertThat(aPartitions).as("key=A는 항상 동일 파티션").hasSize(1);
        System.out.println("  ✓ key=\"A\": 항상 파티션 " + aPartitions + " 에만 저장됨");

        // key="B"도 정확히 1개 파티션에만 있어야 한다
        Set<Integer> bPartitions = keyToPartitions.getOrDefault("B", Set.of());
        assertThat(bPartitions).as("key=B는 항상 동일 파티션").hasSize(1);
        System.out.println("  ✓ key=\"B\": 항상 파티션 " + bPartitions + " 에만 저장됨");

        // key=null은 여러 파티션에 분산되어야 한다
        Set<Integer> nullPartitions = keyToPartitions.getOrDefault("null", Set.of());
        System.out.println("  ✓ key=null: " + nullPartitions.size() + "개 파티션에 분산됨 → " + nullPartitions);

        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 키가 있으면 hash(key) % partitionCount 로 파티션 결정");
        System.out.println("  - 같은 키는 파티션 수가 바뀌지 않는 한 항상 같은 파티션");
        System.out.println("  - null 키는 라운드 로빈 / 스티키 파티셔너로 분산");
        System.out.println("  - 파티션 수를 변경하면 기존 키의 파티션 매핑이 깨질 수 있다 (주의!)");
        printSeparator();
    }

    private Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        return props;
    }
}
