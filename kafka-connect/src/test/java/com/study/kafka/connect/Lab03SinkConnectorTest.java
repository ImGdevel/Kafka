package com.study.kafka.connect;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.study.kafka.connect.ConnectHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 03 — Sink Connector: 카프카 → 파일
 *
 * 검증 명제: "Source + Sink = 애플리케이션 코드 없는 완전한 파이프라인"
 *
 * Q1. FileStream Sink 등록 → 프로듀서로 직접 전송 → 파일에 도착 확인
 * Q2. Lab02 Source와 연결: 파일 → Source → 토픽 → Sink → 파일 (코드 0줄)
 * Q3. Sink Connector 재시작 후 중복 없음 확인 (at-least-once 배달 보장)
 *
 * 실행 방법:
 *   docker compose --profile connect up -d
 *   ./gradlew :kafka-connect:test -Dgroups=lab -Dtest=Lab03SinkConnectorTest --info
 */
@Tag("lab")
@DisplayName("Lab 03 — Sink Connector: 카프카 → 파일")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab03SinkConnectorTest {

    private static final String TOPIC_Q1          = "lab03-direct";
    private static final String TOPIC_Q2          = "lab03-pipeline";
    private static final String SINK_CONNECTOR_Q1 = "lab03-sink-q1";
    private static final String SOURCE_CONNECTOR   = "lab03-source";
    private static final String SINK_CONNECTOR_Q2  = "lab03-sink-q2";
    private static final String SOURCE_FILE        = DATA_DIR + "/lab03-source.txt";
    private static final String SINK_FILE_Q1       = DATA_DIR + "/lab03-sink-q1.txt";
    private static final String SINK_FILE_Q2       = DATA_DIR + "/lab03-sink-q2.txt";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        assumeTrue(isConnectAvailable(), "Kafka Connect 워커(localhost:8083)가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 03: Sink Connector — 카프카 → 파일 (9.4)");
        System.out.println("=".repeat(62));
        createTopic(TOPIC_Q1, 1, (short) 3);
        createTopic(TOPIC_Q2, 1, (short) 3);
        cleanConnector(SINK_CONNECTOR_Q1);
        cleanConnector(SOURCE_CONNECTOR);
        cleanConnector(SINK_CONNECTOR_Q2);
        resetSourceOffset(SOURCE_CONNECTOR, SOURCE_FILE);
        removeConnectFile(SOURCE_FILE);
        removeConnectFile(SINK_FILE_Q1);
        removeConnectFile(SINK_FILE_Q2);
        writeToConnectFile(SOURCE_FILE, "");
    }

    @AfterAll
    static void tearDown() {
        cleanConnector(SINK_CONNECTOR_Q1);
        cleanConnector(SOURCE_CONNECTOR);
        cleanConnector(SINK_CONNECTOR_Q2);
        deleteTopic(TOPIC_Q1);
        deleteTopic(TOPIC_Q2);
        removeConnectFile(SOURCE_FILE);
        removeConnectFile(SINK_FILE_Q1);
        removeConnectFile(SINK_FILE_Q2);
    }

    /**
     * Q1. 기본 Sink: 토픽 → 파일
     *
     * KafkaProducer로 직접 토픽에 메시지를 전송하고
     * FileStream Sink Connector가 이를 컨테이너 내 파일로 기록한다.
     * Source 없이 Sink만으로도 파이프라인의 절반을 구성할 수 있다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1: Sink Connector가 토픽 메시지를 파일로 기록한다")
    void sink_connector_writes_topic_messages_to_file() throws Exception {
        System.out.println("\n  Q1: 기본 Sink Connector 파이프라인");
        System.out.println("  Producer → " + TOPIC_Q1 + " → [FileStream Sink] → " + SINK_FILE_Q1);

        // Sink Connector 등록
        postJson("/connectors", buildSinkConfig(SINK_CONNECTOR_Q1, SINK_FILE_Q1, TOPIC_Q1));
        waitForState(SINK_CONNECTOR_Q1, "RUNNING", 30_000);
        System.out.println("  Sink Connector 등록: RUNNING");

        // 프로듀서로 직접 메시지 전송
        int sendCount = 5;
        sendMessages(TOPIC_Q1, sendCount, "direct-order");

        System.out.printf("  %s 토픽에 %d개 메시지 전송%n", TOPIC_Q1, sendCount);

        // Sink가 파일에 기록할 때까지 대기
        waitForFileLines(SINK_FILE_Q1, sendCount, 20_000);

        int fileLines = countConnectFileLines(SINK_FILE_Q1);
        String fileContent = readConnectFile(SINK_FILE_Q1);

        System.out.println();
        System.out.println("  파일 내용 (" + SINK_FILE_Q1 + "):");
        for (String line : fileContent.split("\n")) {
            System.out.println("    " + line);
        }
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Sink Connector가 토픽을 구독하고 레코드를 파일에 기록한다");
        System.out.println("  - 각 레코드의 value.toString()이 파일의 한 줄로 저장된다");
        System.out.println("  - 컨슈머 그룹 ID: 'connect-{커넥터이름}' 형태로 자동 생성된다");
        printSeparator();

        assertThat(fileLines).isEqualTo(sendCount);
        assertThat(fileContent).contains("direct-order");
    }

    /**
     * Q2. 완전한 파이프라인: 소스 파일 → 카프카 → 싱크 파일
     *
     * Source Connector + Sink Connector 조합으로
     * 애플리케이션 코드(Producer/Consumer) 없이 파일 간 데이터 이동 파이프라인을 구성한다.
     * 이것이 Kafka Connect의 핵심 가치: "연결 운영의 자동화"
     */
    @Test
    @Order(2)
    @DisplayName("Q2: Source + Sink 조합으로 파일 → 카프카 → 파일 파이프라인 완성 (코드 0줄)")
    void source_and_sink_form_complete_pipeline_without_code() throws Exception {
        System.out.println("\n  Q2: 코드 없는 완전한 파이프라인");
        System.out.println("  " + SOURCE_FILE + " → [Source] → " + TOPIC_Q2 + " → [Sink] → " + SINK_FILE_Q2);

        // Source Connector 등록
        postJson("/connectors", buildSourceConfig(SOURCE_CONNECTOR, SOURCE_FILE, TOPIC_Q2));
        waitForState(SOURCE_CONNECTOR, "RUNNING", 30_000);

        // Sink Connector 등록
        postJson("/connectors", buildSinkConfig(SINK_CONNECTOR_Q2, SINK_FILE_Q2, TOPIC_Q2));
        waitForState(SINK_CONNECTOR_Q2, "RUNNING", 30_000);
        System.out.println("  Source + Sink 커넥터 모두 RUNNING");

        // 소스 파일에 주문 데이터 추가 (파이프라인의 입력)
        int orderCount = 6;
        StringBuilder orders = new StringBuilder();
        for (int i = 1; i <= orderCount; i++) {
            orders.append("{\"orderId\":\"PIPE-").append(String.format("%03d", i))
                  .append("\",\"status\":\"PENDING\",\"amount\":").append(i * 2000).append("}\n");
        }
        appendToConnectFile(SOURCE_FILE, orders.toString());
        System.out.printf("  소스 파일에 %d개 주문 추가%n", orderCount);

        // 파이프라인 완주: 소스 파일 → 카프카 → 싱크 파일
        waitForFileLines(SINK_FILE_Q2, orderCount, 30_000);

        String sinkContent = readConnectFile(SINK_FILE_Q2);
        String sourceContent = readConnectFile(SOURCE_FILE);
        int sinkLines = countConnectFileLines(SINK_FILE_Q2);

        System.out.println();
        System.out.printf("  소스 파일 라인 수: %d개%n", sourceContent.split("\n").length);
        System.out.printf("  싱크 파일 라인 수: %d개%n", sinkLines);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Producer 코드: 0줄 | Consumer 코드: 0줄");
        System.out.println("  - Source 커넥터 설정 ~10줄 + Sink 커넥터 설정 ~10줄로 파이프라인 완성");
        System.out.println("  - 운영자는 REST API로 파이프라인을 CRUD 방식으로 관리한다");
        printSeparator();

        assertThat(sinkLines).isEqualTo(orderCount);
        assertThat(sinkContent).contains("PIPE-001");
    }

    /**
     * Q3. Sink Connector 재시작 — at-least-once 배달 보증
     *
     * Sink Connector는 컨슈머 그룹 오프셋(consumer_offsets 토픽)을 사용한다.
     * 커넥터를 재시작해도 마지막 커밋된 오프셋부터 이어서 읽는다.
     * 단, 마지막 커밋 이후 처리된 레코드는 재처리될 수 있다 (at-least-once).
     */
    @Test
    @Order(3)
    @DisplayName("Q3: Sink Connector 재시작 후 중복 없이 이어서 기록한다 (at-least-once)")
    void sink_connector_resumes_after_restart() throws Exception {
        System.out.println("\n  Q3: Sink Connector 재시작 — 중복 확인");

        // 먼저 현재 싱크 파일 라인 수 기록
        int beforeRestart = countConnectFileLines(SINK_FILE_Q1);
        System.out.printf("  재시작 전 싱크 파일(%s) 라인 수: %d%n", SINK_FILE_Q1, beforeRestart);

        // Sink Connector 재시작 (REST API)
        restartConnector(SINK_CONNECTOR_Q1);
        Thread.sleep(3000);
        waitForState(SINK_CONNECTOR_Q1, "RUNNING", 15_000);
        System.out.println("  Sink Connector 재시작 완료: RUNNING");

        // 재시작 후 잠시 대기 (새 메시지가 없으면 파일이 늘어나지 않아야 함)
        Thread.sleep(3000);
        int afterRestart = countConnectFileLines(SINK_FILE_Q1);
        System.out.printf("  재시작 후 싱크 파일 라인 수: %d%n", afterRestart);

        // 재시작 후 새 메시지 전송 → 파일에 추가
        int newMessages = 3;
        sendMessages(TOPIC_Q1, newMessages, "after-restart");
        waitForFileLines(SINK_FILE_Q1, beforeRestart + newMessages, 15_000);

        int finalCount = countConnectFileLines(SINK_FILE_Q1);
        System.out.printf("  신규 %d개 전송 후 싱크 파일 라인 수: %d%n", newMessages, finalCount);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Sink Connector 오프셋: consumer_offsets 토픽에 저장");
        System.out.println("  - 재시작 시 consumer_offsets에서 오프셋 이어받기");
        System.out.println("  - at-least-once: 커밋 전 재시작 시 재처리 가능 (멱등성 싱크 권장)");
        printSeparator();

        assertThat(afterRestart).isEqualTo(beforeRestart); // 재시작 직후 중복 없음
        assertThat(finalCount).isEqualTo(beforeRestart + newMessages);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private static String buildSourceConfig(String name, String file, String topic) {
        return """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
                    "tasks.max": "1",
                    "file": "%s",
                    "topic": "%s"
                  }
                }""".formatted(name, file, topic);
    }

    private static String buildSinkConfig(String name, String file, String topic) {
        return """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
                    "tasks.max": "1",
                    "file": "%s",
                    "topics": "%s"
                  }
                }""".formatted(name, file, topic);
    }

    private void sendMessages(String topic, int count, String prefix) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");

        CountDownLatch latch = new CountDownLatch(count);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 1; i <= count; i++) {
                String value = "{\"orderId\":\"" + prefix + "-" + String.format("%03d", i) + "\"}";
                producer.send(new ProducerRecord<>(topic, null, value), (m, e) -> latch.countDown());
            }
            producer.flush();
        }
        latch.await(15, TimeUnit.SECONDS);
    }

    private void waitForFileLines(String containerPath, int expectedLines, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        System.out.print("  파일 기록 대기");
        while (System.currentTimeMillis() < deadline) {
            int lines = countConnectFileLines(containerPath);
            if (lines >= expectedLines) {
                System.out.println(" (" + lines + "줄)");
                return;
            }
            System.out.print(".");
            Thread.sleep(1000);
        }
        System.out.println();
        System.err.println("  경고: 타임아웃 — 파일 라인 수가 " + expectedLines + "에 도달하지 못했습니다.");
    }
}
