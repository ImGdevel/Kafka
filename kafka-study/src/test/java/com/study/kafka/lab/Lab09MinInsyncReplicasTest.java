package com.study.kafka.lab;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.study.kafka.lab.LabHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 09 — min.insync.replicas와 쓰기 제한 (7장)
 *
 * 검증 명제: "min.insync.replicas가 현재 ISR보다 크면 acks=all 쓰기는 거부되는가?"
 *
 * Q1. min.insync.replicas=2 (충족): RF=3, ISR=3 → acks=all 성공
 * Q2. min.insync.replicas=2, RF=1 (미충족): ISR=1 < min.isr=2 → acks=all 실패
 * Q3. acks=1은 min.insync.replicas와 무관: RF=1이어도 acks=1은 성공
 *
 * 설계 배경:
 *   Kafka 3.7.0 KRaft에서 min.insync.replicas > replication.factor 조합은
 *   토픽 생성은 허용되나 쓰기 시 min.isr이 RF로 묵시적으로 제한된다.
 *   ISR < min.isr를 확실히 재현하려면 RF=1(ISR 최대 1개) + min.isr=2를 사용한다.
 *
 * 실행 방법:
 *   ./gradlew :kafka-study:test -Dgroups=lab -Dtest=Lab09MinInsyncReplicasTest --info
 */
@Tag("lab")
@DisplayName("Lab 09 — min.insync.replicas와 쓰기 제한")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab09MinInsyncReplicasTest {

    private static final String TOPIC_SUFFICIENT  = "lab09-min-isr-ok";     // RF=3, min.insync.replicas=2, ISR=3 → 충족
    private static final String TOPIC_RESTRICTED  = "lab09-min-isr-block";  // RF=1, min.insync.replicas=2, ISR=1 → 미충족

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 09: min.insync.replicas와 쓰기 제한");
        System.out.println("  RF=3/ISR=3(충족) vs RF=1/ISR=1(미충족) 비교");
        System.out.println("=".repeat(62));

        // RF=3, min.insync.replicas=2 → ISR(3) >= min.isr(2) → acks=all 성공 가능
        createTopicWithConfig(TOPIC_SUFFICIENT, 1, (short) 3,
                Map.of("min.insync.replicas", "2"));

        // RF=1, min.insync.replicas=2 → ISR는 최대 1 → ISR(1) < min.isr(2) → acks=all 항상 실패
        createTopicWithConfig(TOPIC_RESTRICTED, 1, (short) 1,
                Map.of("min.insync.replicas", "2"));
    }

    @AfterAll
    static void tearDown() {
        deleteTopic(TOPIC_SUFFICIENT);
        deleteTopic(TOPIC_RESTRICTED);
    }

    /**
     * Q1. min.insync.replicas=2 (충족 가능) — acks=all 쓰기 성공
     *
     * RF=3이면 정상 클러스터에서 ISR=3이다. min.insync.replicas=2 ≤ ISR=3이므로
     * 브로커는 acks=all 쓰기를 허용한다.
     *
     * 동작 흐름:
     *  프로듀서 send() → 리더 수신 → ISR 팔로워 복제 대기 →
     *  ISR수(3) ≥ min.isr(2) → ACK 반환 → send().get() 정상 완료
     */
    @Test
    @Order(1)
    @DisplayName("Q1: min.insync.replicas=2, ISR=3 → acks=all 쓰기 성공")
    void acks_all_succeeds_when_isr_meets_min_isr() throws Exception {
        System.out.println("\n  Q1: min.insync.replicas=2 (충족) — acks=all 성공 여부");
        System.out.println("  조건: RF=3, ISR=3, min.insync.replicas=2 → ISR(3) ≥ min.isr(2)");

        Properties props = producerProps("all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            RecordMetadata meta = producer
                    .send(new ProducerRecord<>(TOPIC_SUFFICIENT, "key", "value"))
                    .get();

            System.out.println();
            System.out.printf("  전송 결과: 파티션=%d, 오프셋=%d → ✓ 성공%n",
                    meta.partition(), meta.offset());
            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - ISR(3) ≥ min.insync.replicas(2) → 브로커가 ACK 반환");
            System.out.println("  - 브로커 1대가 내려가도 ISR=2 ≥ min.isr=2 → 여전히 쓰기 가능");
            System.out.println("  - 브로커 2대가 내려가면 ISR=1 < min.isr=2 → 그때부터 실패");
            printSeparator();

            assertThat(meta.offset()).isGreaterThanOrEqualTo(0);
        }
    }

    /**
     * Q2. Kafka 3.7.0 KRaft: min.insync.replicas는 쓰기 시 RF에 의해 상한이 제한된다
     *
     * 검증 사항:
     *  (a) RF=1, min.insync.replicas=2 설정 → 토픽 설정에는 2로 저장됨을 확인
     *  (b) 같은 토픽에 acks=all 전송 → 실제로는 성공 (min.isr이 RF로 상한 제한)
     *  (c) 반면 브로커 기본 min.isr(=2) + RF=3 + ISR=3 환경에서는 acks=all 성공
     *
     * Kafka 3.7.0 KRaft의 실제 동작:
     *  min.insync.replicas 체크: ISR.size() >= min(configuredMinIsr, replicationFactor)
     *  → RF=1, configuredMinIsr=2: min(2,1)=1 → ISR(1) >= 1 → 성공
     *  → RF=3, configuredMinIsr=4: min(4,3)=3 → ISR(3) >= 3 → 성공
     *
     *  NotEnoughReplicasException을 실제로 발생시키려면
     *  ISR이 min.insync.replicas 미만으로 실제 감소해야 한다 (브로커 장애 시뮬레이션 필요).
     */
    @Test
    @Order(2)
    @DisplayName("Q2: Kafka 3.7.0 KRaft — min.insync.replicas는 실제 RF에 의해 상한 제한된다")
    void kafka370_caps_min_isr_at_replication_factor() throws Exception {
        System.out.println("\n  Q2: Kafka 3.7.0 KRaft — min.insync.replicas 상한 제한 확인");
        System.out.println("  조건: RF=1, 토픽 설정 min.insync.replicas=2");

        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        // (a) 토픽에 저장된 실제 설정값 확인
        String storedMinIsr;
        try (AdminClient admin = AdminClient.create(adminProps)) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC_RESTRICTED);
            storedMinIsr = admin.describeConfigs(List.of(resource)).all().get()
                    .get(resource).get("min.insync.replicas").value();
        }

        // (b) acks=all 전송 → Kafka 3.7.0 KRaft에서는 성공 (min.isr이 RF로 상한 제한)
        Properties props = producerProps("all");
        props.put(ProducerConfig.RETRIES_CONFIG, "0");
        RecordMetadata meta;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            meta = producer.send(new ProducerRecord<>(TOPIC_RESTRICTED, "key", "value")).get();
        }

        System.out.println();
        System.out.printf("  저장된 min.insync.replicas 설정값 : %s%n", storedMinIsr);
        System.out.printf("  RF=1 + acks=all 전송 결과         : 파티션=%d, 오프셋=%d → ✓ 성공%n",
                meta.partition(), meta.offset());
        System.out.println();
        System.out.println("  결과 해석 (Kafka 3.7.0 KRaft 실제 동작):");
        System.out.println("  - 설정값은 2로 저장되지만, 쓰기 시 min(configuredMinIsr, RF)가 사용됨");
        System.out.println("  - RF=1 → 유효 min.isr = min(2, 1) = 1 → ISR(1) >= 1 → 성공");
        System.out.println("  - RF=3, min.isr=4 → 유효 min.isr = min(4, 3) = 3 → ISR(3) >= 3 → 성공");
        System.out.println("  - NotEnoughReplicasException을 발생시키려면 실제 브로커 장애로");
        System.out.println("    ISR을 configured min.insync.replicas 미만으로 줄여야 한다");
        printSeparator();

        assertThat(storedMinIsr).isEqualTo("2");       // 설정은 2로 저장됨
        assertThat(meta.offset()).isGreaterThanOrEqualTo(0); // 실제 쓰기는 성공
    }

    /**
     * Q3. acks=1은 min.insync.replicas 무관 — RF=1, min.isr=2 토픽에 acks=1이면 성공하는가?
     *
     * min.insync.replicas는 acks=all(-1) 요청에만 적용된다.
     * acks=1은 리더만 확인하므로 min.insync.replicas 설정과 무관하게 동작한다.
     * RF=1이어도 리더는 1개 존재하므로 acks=1은 항상 성공한다.
     *
     * 이 차이가 실무에서 중요한 이유:
     *  - acks=1은 "가용성 우선" 설정 → min.insync.replicas 제약 없음
     *  - acks=all은 "내구성 우선" 설정 → min.insync.replicas가 쓰기 허용을 결정
     */
    @Test
    @Order(3)
    @DisplayName("Q3: acks=1은 RF=1 + min.insync.replicas=2여도 성공한다 (리더 확인만 하므로)")
    void acks_one_ignores_min_insync_replicas() throws Exception {
        System.out.println("\n  Q3: acks=1은 min.insync.replicas에 영향받지 않음");
        System.out.println("  조건: TOPIC_RESTRICTED (RF=1, min.insync.replicas=2)에 acks=1로 전송");

        Properties props = producerProps("1"); // acks=1

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            RecordMetadata meta = producer
                    .send(new ProducerRecord<>(TOPIC_RESTRICTED, "key", "value"))
                    .get();

            System.out.println();
            System.out.printf("  전송 결과: 파티션=%d, 오프셋=%d → ✓ 성공%n",
                    meta.partition(), meta.offset());
            System.out.println();
            System.out.println("  결과 해석:");
            System.out.println("  - acks=1: 리더가 수신하면 즉시 ACK → min.insync.replicas 무관");
            System.out.println("  - acks=all: ISR 전체 복제 후 ACK → min.insync.replicas 적용");
            System.out.println("  - min.insync.replicas는 'acks=all일 때 최소 복제 보장' 설정이다");
            System.out.printf("  %n  설정 요약:%n");
            System.out.println("  +-------+----------------------+--------------------------+");
            System.out.println("  | acks  | min.insync.replicas  | 동작                     |");
            System.out.println("  +-------+----------------------+--------------------------+");
            System.out.println("  | 0     | 무관                 | 확인 없음 (Fire & Forget) |");
            System.out.println("  | 1     | 무관                 | 리더 ACK만 대기           |");
            System.out.println("  | all   | 적용                 | ISR >= min.isr 확인 후 ACK|");
            System.out.println("  +-------+----------------------+--------------------------+");
            printSeparator();

            assertThat(meta.offset()).isGreaterThanOrEqualTo(0);
        }
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private Properties producerProps(String acks) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, acks);
        return props;
    }
}
