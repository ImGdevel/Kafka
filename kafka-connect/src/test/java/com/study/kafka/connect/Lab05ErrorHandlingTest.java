package com.study.kafka.connect;

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

import static com.study.kafka.connect.ConnectHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 05 — 에러 처리와 Dead Letter Queue
 *
 * 검증 명제: "errors.tolerance + DLQ는 불량 레코드가 파이프라인 전체를 멈추지 않게 한다"
 *
 * 시나리오: JSON을 파싱해야 하는 Sink에 일부 잘못된 형식의 레코드가 들어온다.
 *
 * Q1. errors.tolerance=none (기본값): 첫 불량 레코드에 커넥터 전체가 FAILED
 * Q2. errors.tolerance=all: 불량 레코드 건너뛰고 정상 레코드 처리 (조용한 유실)
 * Q3. errors.tolerance=all + DLQ: 불량 레코드가 DLQ 토픽에 보존 → 나중에 재처리 가능
 *
 * 실행 방법:
 *   docker compose --profile connect up -d
 *   ./gradlew :kafka-connect:test -Dgroups=lab -Dtest=Lab05ErrorHandlingTest --info
 */
@Tag("lab")
@DisplayName("Lab 05 — 에러 처리와 Dead Letter Queue")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab05ErrorHandlingTest {

    private static final String TOPIC_Q1     = "lab05-orders-q1";
    private static final String TOPIC_Q2     = "lab05-orders-q2";
    private static final String TOPIC_Q3     = "lab05-orders-q3";
    private static final String DLQ_TOPIC    = "lab05-dlq";
    private static final String CONNECTOR_Q1 = "lab05-sink-q1";
    private static final String CONNECTOR_Q2 = "lab05-sink-q2";
    private static final String CONNECTOR_Q3 = "lab05-sink-q3";
    private static final String SINK_FILE_Q2 = DATA_DIR + "/lab05-output-q2.txt";
    private static final String SINK_FILE_Q3 = DATA_DIR + "/lab05-output-q3.txt";

    // 정상 레코드 (유효한 JSON)
    private static final String GOOD_JSON =
            "{\"orderId\":\"ORD-%03d\",\"amount\":%d}";
    // 불량 레코드 (JSON 파싱 불가)
    private static final String BAD_RECORD = "THIS_IS_NOT_JSON#$%^&*()";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        assumeTrue(isConnectAvailable(), "Kafka Connect 워커(localhost:8083)가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 05: 에러 처리와 Dead Letter Queue (9.6)");
        System.out.println("  핵심: 불량 레코드를 어떻게 다룰 것인가?");
        System.out.println("=".repeat(62));
        createTopic(TOPIC_Q1, 1, (short) 3);
        createTopic(TOPIC_Q2, 1, (short) 3);
        createTopic(TOPIC_Q3, 1, (short) 3);
        createTopic(DLQ_TOPIC, 1, (short) 3);
        cleanConnector(CONNECTOR_Q1);
        cleanConnector(CONNECTOR_Q2);
        cleanConnector(CONNECTOR_Q3);
        removeConnectFile(SINK_FILE_Q2);
        removeConnectFile(SINK_FILE_Q3);
    }

    @AfterAll
    static void tearDown() {
        cleanConnector(CONNECTOR_Q1);
        cleanConnector(CONNECTOR_Q2);
        cleanConnector(CONNECTOR_Q3);
        deleteTopic(TOPIC_Q1);
        deleteTopic(TOPIC_Q2);
        deleteTopic(TOPIC_Q3);
        deleteTopic(DLQ_TOPIC);
        removeConnectFile(SINK_FILE_Q2);
        removeConnectFile(SINK_FILE_Q3);
    }

    /**
     * Q1. errors.tolerance=none (기본값) — 첫 불량 레코드에 FAILED
     *
     * Sink가 JSON으로 파싱할 수 없는 레코드를 만나면 즉시 에러가 발생한다.
     * errors.tolerance=none이면 커넥터 전체가 FAILED 상태로 전환되고 처리가 중단된다.
     * 파이프라인 전체가 멈추는 위험이 있다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1: errors.tolerance=none — 불량 레코드 1개에 커넥터 전체가 FAILED로 전환된다")
    void no_tolerance_causes_connector_failure_on_bad_record() throws Exception {
        System.out.println("\n  Q1: errors.tolerance=none (기본값)");
        System.out.println("  시나리오: 정상 3개 + 불량 1개 + 정상 2개 전송");

        // 파일 없는 Sink (파일 없어도 Sink Connector는 시작 가능 — 파일 경로를 생략)
        String connectorConfig = """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
                    "tasks.max": "1",
                    "topics": "%s",
                    "file": "%s",
                    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
                    "value.converter.schemas.enable": "false",
                    "errors.tolerance": "none"
                  }
                }""".formatted(CONNECTOR_Q1, TOPIC_Q1, DATA_DIR + "/lab05-q1.txt");

        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_Q1, "RUNNING", 30_000);
        System.out.println("  Sink Connector 등록: RUNNING");

        // 불량 레코드 전송 (정상 3 + 불량 1 + 정상 2)
        sendMixedRecords(TOPIC_Q1, 3, 1, 2);
        System.out.println("  정상 3개 + 불량 1개 + 정상 2개 전송 완료");

        // 커넥터가 FAILED 상태로 전환될 때까지 대기
        System.out.print("  커넥터 FAILED 대기");
        long deadline = System.currentTimeMillis() + 30_000;
        String finalState = "RUNNING";
        while (System.currentTimeMillis() < deadline) {
            finalState = getConnectorState(CONNECTOR_Q1);
            if ("FAILED".equals(finalState)) {
                System.out.println();
                break;
            }
            System.out.print(".");
            Thread.sleep(1000);
        }

        System.out.println();
        System.out.printf("  커넥터 최종 상태: %s%n", finalState);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 불량 레코드(JSON 파싱 실패)에서 에러 발생");
        System.out.println("  - errors.tolerance=none → 즉시 task FAILED → connector FAILED");
        System.out.println("  - 불량 레코드 이후의 정상 레코드도 처리 중단됨");
        System.out.println("  - 수동으로 커넥터를 재시작하거나 토픽의 불량 레코드를 건너뛰어야 함");
        printSeparator();

        assertThat(finalState).isEqualTo("FAILED");
    }

    /**
     * Q2. errors.tolerance=all — 불량 레코드 건너뛰기 (조용한 유실)
     *
     * 불량 레코드를 만나도 커넥터가 FAILED 상태가 되지 않는다.
     * 대신 불량 레코드를 조용히 건너뛰고(drop) 다음 레코드를 처리한다.
     * 불량 레코드는 흔적 없이 사라진다 — 데이터 유실 위험.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: errors.tolerance=all — 불량 레코드를 건너뛰고 정상 레코드만 처리한다")
    void all_tolerance_skips_bad_records_silently() throws Exception {
        System.out.println("\n  Q2: errors.tolerance=all (조용한 건너뛰기)");
        System.out.println("  시나리오: 정상 5개 + 불량 2개 혼합 → 정상 5개만 싱크에 도달해야 함");

        String connectorConfig = """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
                    "tasks.max": "1",
                    "topics": "%s",
                    "file": "%s",
                    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
                    "value.converter.schemas.enable": "false",
                    "errors.tolerance": "all"
                  }
                }""".formatted(CONNECTOR_Q2, TOPIC_Q2, SINK_FILE_Q2);

        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_Q2, "RUNNING", 30_000);
        System.out.println("  Sink Connector 등록: RUNNING");

        int goodCount = 5, badCount = 2;
        sendMixedRecords(TOPIC_Q2, goodCount, badCount, 0);
        System.out.printf("  정상 %d개 + 불량 %d개 전송 완료%n", goodCount, badCount);

        // 정상 레코드가 모두 파일에 기록될 때까지 대기
        waitForFileLines(SINK_FILE_Q2, goodCount, 20_000);

        int fileLines = countConnectFileLines(SINK_FILE_Q2);
        String state = getConnectorState(CONNECTOR_Q2);

        System.out.println();
        System.out.printf("  커넥터 상태: %s (FAILED 아님)%n", state);
        System.out.printf("  파일 기록 라인 수: %d개 (정상=%d, 불량=%d 사라짐)%n",
                fileLines, goodCount, badCount);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 커넥터는 RUNNING 유지 — 불량 레코드가 파이프라인을 멈추지 않음");
        System.out.println("  - 불량 레코드 " + badCount + "개가 조용히 사라짐 (흔적 없음)");
        System.out.println("  - 이것이 '조용한 데이터 유실(silent data loss)'의 위험");
        System.out.println("  - 운영 환경에서는 DLQ를 함께 설정해야 한다 (→ Q3)");
        printSeparator();

        assertThat(state).isEqualTo("RUNNING");
        assertThat(fileLines).isEqualTo(goodCount);
    }

    /**
     * Q3. errors.tolerance=all + Dead Letter Queue
     *
     * 불량 레코드를 지정된 DLQ 토픽으로 라우팅한다.
     * 불량 레코드는 사라지지 않고 DLQ에 보존된다.
     * 운영자는 DLQ를 모니터링하고 나중에 재처리할 수 있다.
     * 이것이 Kafka Connect의 권장 에러 처리 패턴이다.
     */
    @Test
    @Order(3)
    @DisplayName("Q3: DLQ 설정 — 불량 레코드가 DLQ 토픽에 보존되어 나중에 재처리할 수 있다")
    void dlq_preserves_bad_records_for_later_reprocessing() throws Exception {
        System.out.println("\n  Q3: Dead Letter Queue (DLQ) 패턴");
        System.out.println("  시나리오: 불량 레코드 → DLQ 토픽 보관, 정상 레코드 → 파일");

        String connectorConfig = """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
                    "tasks.max": "1",
                    "topics": "%s",
                    "file": "%s",
                    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
                    "value.converter.schemas.enable": "false",
                    "errors.tolerance": "all",
                    "errors.deadletterqueue.topic.name": "%s",
                    "errors.deadletterqueue.context.headers.enable": "true",
                    "errors.deadletterqueue.topic.replication.factor": "1"
                  }
                }""".formatted(CONNECTOR_Q3, TOPIC_Q3, SINK_FILE_Q3, DLQ_TOPIC);

        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_Q3, "RUNNING", 30_000);
        System.out.println("  Sink Connector + DLQ 등록: RUNNING");
        System.out.println("  DLQ 토픽: " + DLQ_TOPIC);

        int goodCount = 4, badCount = 3;
        sendMixedRecords(TOPIC_Q3, goodCount, badCount, 0);
        System.out.printf("  정상 %d개 + 불량 %d개 전송 완료%n", goodCount, badCount);

        // 정상 레코드가 파일에 기록될 때까지 대기
        waitForFileLines(SINK_FILE_Q3, goodCount, 20_000);
        Thread.sleep(3000); // DLQ 라우팅 완료 대기

        // 파일 기록 확인
        int fileLines = countConnectFileLines(SINK_FILE_Q3);

        // DLQ 토픽에서 불량 레코드 확인
        List<ConsumerRecord<String, String>> dlqRecords =
                consumeAll(DLQ_TOPIC, "lab05-dlq-reader-" + System.nanoTime(), badCount, 10_000);

        System.out.println();
        System.out.printf("  파일 기록 라인 수: %d개 (정상만)%n", fileLines);
        System.out.printf("  DLQ 수신 레코드: %d개 (불량)%n", dlqRecords.size());
        System.out.println();

        if (!dlqRecords.isEmpty()) {
            System.out.println("  DLQ 레코드 내용 (헤더 포함):");
            for (int i = 0; i < Math.min(dlqRecords.size(), 2); i++) {
                ConsumerRecord<String, String> r = dlqRecords.get(i);
                System.out.println("    [" + i + "] value: " + r.value());
                r.headers().forEach(h ->
                        System.out.println("         header: " + h.key() + "=" +
                                           new String(h.value())));
            }
        }
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 정상 " + goodCount + "개 → 파일 기록 완료");
        System.out.println("  - 불량 " + badCount + "개 → DLQ 토픽(" + DLQ_TOPIC + ")에 보관");
        System.out.println("  - DLQ 헤더: 에러 원인, 소스 토픽/파티션/오프셋, 커넥터명 포함");
        System.out.println("  - 운영자는 DLQ를 모니터링하고 불량 레코드를 수정 후 재처리 가능");
        System.out.println("  - 이것이 Connect의 권장 에러 처리 패턴: silence + DLQ");
        printSeparator();

        assertThat(fileLines).isEqualTo(goodCount);
        assertThat(dlqRecords.size()).isEqualTo(badCount);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    /**
     * 정상(goodCount) + 불량(badCount) + 추가 정상(trailingGood) 레코드를 순서대로 전송한다.
     * 불량 레코드는 중간에 끼워 넣는다.
     */
    private void sendMixedRecords(String topic, int goodBefore, int bad, int goodAfter)
            throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        int total = goodBefore + bad + goodAfter;
        CountDownLatch latch = new CountDownLatch(total);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            // 정상 레코드 (앞부분)
            for (int i = 1; i <= goodBefore; i++) {
                String value = String.format(GOOD_JSON, i, i * 10000);
                producer.send(new ProducerRecord<>(topic, null, value), (m, e) -> latch.countDown());
            }
            // 불량 레코드
            for (int i = 0; i < bad; i++) {
                producer.send(new ProducerRecord<>(topic, null, BAD_RECORD + "_" + i),
                        (m, e) -> latch.countDown());
            }
            // 정상 레코드 (뒷부분)
            for (int i = goodBefore + 1; i <= goodBefore + goodAfter; i++) {
                String value = String.format(GOOD_JSON, i, i * 10000);
                producer.send(new ProducerRecord<>(topic, null, value), (m, e) -> latch.countDown());
            }
            producer.flush();
        }
        latch.await(15, TimeUnit.SECONDS);
    }

    private void waitForFileLines(String path, int expectedLines, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        System.out.print("  파일 기록 대기");
        while (System.currentTimeMillis() < deadline) {
            int lines = countConnectFileLines(path);
            if (lines >= expectedLines) {
                System.out.println(" (" + lines + "줄)");
                return;
            }
            System.out.print(".");
            Thread.sleep(1000);
        }
        System.out.println();
    }
}
