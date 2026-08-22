package com.study.kafka.connect;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Properties;

import static com.study.kafka.connect.ConnectHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 01 — Connect의 존재 이유
 *
 * 검증 명제: "오프셋 추적과 재시작 복구는 Connect가 제공하는 핵심 운영 기능이다"
 *
 * Q1. 직접 구현(Java 코드)으로 파일을 읽고 Kafka로 전송한다.
 *     재시작하면 처음부터 다시 읽는다 → 중복 발생
 *
 * Q2. FileStream Source Connector로 동일 파일을 읽는다.
 *     커넥터를 삭제 후 재생성해도 같은 이름이면 오프셋을 이어받는다 → 중복 없음
 *
 * Q3. connect-offsets 토픽에서 오프셋이 어떻게 기록되는지 직접 읽어본다.
 *
 * 실행 방법:
 *   docker compose --profile connect up -d
 *   ./gradlew :kafka-connect:test -Dgroups=lab -Dtest=Lab01WhyConnectTest --info
 */
@Tag("lab")
@DisplayName("Lab 01 — Connect의 존재 이유")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab01WhyConnectTest {

    private static final String TOPIC_MANUAL   = "lab01-manual";
    private static final String TOPIC_CONNECT  = "lab01-connect";
    private static final String CONNECTOR_NAME = "lab01-source";
    private static final String SOURCE_FILE    = DATA_DIR + "/lab01-orders.txt";
    private static final int    LINE_COUNT     = 5;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        assumeTrue(isConnectAvailable(), "Kafka Connect 워커(localhost:8083)가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 01: Connect의 존재 이유");
        System.out.println("  핵심: Connect가 왜 단순 Producer보다 나은가?");
        System.out.println("=".repeat(62));
        createTopic(TOPIC_MANUAL, 1, (short) 3);
        createTopic(TOPIC_CONNECT, 1, (short) 3);
        cleanConnector(CONNECTOR_NAME);
        resetSourceOffset(CONNECTOR_NAME, SOURCE_FILE);
        removeConnectFile(SOURCE_FILE);
    }

    @AfterAll
    static void tearDown() {
        cleanConnector(CONNECTOR_NAME);
        deleteTopic(TOPIC_MANUAL);
        deleteTopic(TOPIC_CONNECT);
        removeConnectFile(SOURCE_FILE);
    }

    /**
     * Q1. 직접 구현 — 재시작 시 중복 발생
     *
     * 오프셋을 직접 관리하지 않는 Producer는 재시작 시 파일을 처음부터 다시 읽는다.
     * 이것이 프레임워크 없이 외부 데이터를 Kafka로 수집할 때 발생하는 전형적인 문제다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1: 직접 구현(Java) — 재시작 시 중복 메시지가 발생한다")
    void manual_producer_duplicates_on_restart() throws Exception {
        System.out.println("\n  Q1: 직접 구현 — 재시작 시 중복 발생");
        System.out.println("  시나리오: 주문 데이터를 Java 코드로 직접 Kafka에 전송");

        // 로컬 임시 파일 생성 (직접 구현은 로컬 파일 접근)
        Path tempFile = Files.createTempFile("lab01-orders", ".txt");
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= LINE_COUNT; i++) {
            lines.append("ORDER-").append(String.format("%03d", i))
                 .append(",product-").append(i)
                 .append(",").append(i * 100).append("원\n");
        }
        Files.writeString(tempFile, lines.toString());

        System.out.printf("%n  파일 내용 (%d개 주문):%n", LINE_COUNT);
        Files.readAllLines(tempFile).forEach(l -> System.out.println("    " + l));

        // 첫 번째 실행: 파일 전체를 읽어 Kafka로 전송
        readFileAndProduce(tempFile, TOPIC_MANUAL, "run1");
        Thread.sleep(1000);

        var afterFirstRun = consumeAll(TOPIC_MANUAL, "lab01-consumer-q1-" + System.nanoTime(),
                LINE_COUNT, 10_000);

        System.out.printf("%n  [첫 번째 실행 후] 토픽 메시지 수: %d개%n", afterFirstRun.size());

        // 두 번째 실행: 재시작 — 오프셋 정보 없음, 파일을 처음부터 다시 읽음
        readFileAndProduce(tempFile, TOPIC_MANUAL, "run2");
        Thread.sleep(1000);

        var afterRestart = consumeAll(TOPIC_MANUAL, "lab01-consumer-q1-restart-" + System.nanoTime(),
                LINE_COUNT * 2, 10_000);

        System.out.printf("  [재시작 후] 토픽 메시지 수: %d개%n", afterRestart.size());
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 직접 구현은 '어디까지 읽었는지' 기록하지 않는다");
        System.out.println("  - 재시작 시 파일을 처음부터 다시 읽어 중복 메시지 발생");
        System.out.println("  - 중복 처리(at-least-once) 또는 멱등성 처리가 소비자 책임이 됨");
        printSeparator();

        Files.deleteIfExists(tempFile);

        assertThat(afterFirstRun).hasSize(LINE_COUNT);
        assertThat(afterRestart.size()).isGreaterThanOrEqualTo(LINE_COUNT * 2); // 중복 확인
    }

    /**
     * Q2. FileStream Source Connector — 재시작 후 오프셋 이어받기
     *
     * Connect는 오프셋을 connect-offsets 토픽에 커밋한다.
     * 같은 이름의 커넥터를 삭제 후 재등록하면 동일 오프셋을 이어받는다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: FileStream Source Connector — 삭제 후 재등록해도 오프셋을 이어받는다")
    void connector_resumes_from_offset_after_restart() throws Exception {
        System.out.println("\n  Q2: Connect 오프셋 이어받기");
        System.out.println("  시나리오: 동일 커넥터 이름으로 삭제 후 재등록 → 중복 없음");

        // 소스 파일 생성 (ASCII-only — 멀티바이트 문자는 FileStreamSourceTask offset 추적 오차 유발)
        StringBuilder lines = new StringBuilder();
        for (int i = 1; i <= LINE_COUNT; i++) {
            lines.append("ORDER-").append(String.format("%03d", i))
                 .append(",product-").append(i)
                 .append(",amount-").append(i * 100).append("\n");
        }
        writeToConnectFile(SOURCE_FILE, lines.toString());

        System.out.printf("  소스 파일 작성: %s (%d줄)%n", SOURCE_FILE, LINE_COUNT);

        // 커넥터 등록
        String connectorConfig = """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
                    "tasks.max": "1",
                    "file": "%s",
                    "topic": "%s"
                  }
                }""".formatted(CONNECTOR_NAME, SOURCE_FILE, TOPIC_CONNECT);

        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_NAME, "RUNNING", 30_000);
        System.out.println("  커넥터 상태: RUNNING");

        // 첫 번째 실행 후 메시지 수집
        var firstBatch = consumeAll(TOPIC_CONNECT, "lab01-q2-first-" + System.nanoTime(),
                LINE_COUNT, 15_000);
        System.out.printf("  [첫 번째 실행] 수신 메시지: %d개%n", firstBatch.size());

        // 커넥터 삭제 → 재등록 (같은 이름)
        System.out.println("  커넥터 삭제 중...");
        deleteConnector(CONNECTOR_NAME);
        Thread.sleep(2000);

        System.out.println("  커넥터 재등록 (같은 이름: " + CONNECTOR_NAME + ")");
        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_NAME, "RUNNING", 30_000);

        // 재등록 후 일정 시간 대기 (새 메시지가 없음을 확인)
        Thread.sleep(5000);

        // 새 컨슈머 그룹으로 전체 오프셋부터 읽기
        var afterRestart = consumeAll(TOPIC_CONNECT, "lab01-q2-restart-" + System.nanoTime(),
                LINE_COUNT + 1, 5_000);

        System.out.printf("  [재등록 후] 총 수신 가능 메시지: %d개%n", afterRestart.size());
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Connect는 오프셋을 connect-offsets 토픽에 커밋한다");
        System.out.println("  - 오프셋 키: [커넥터이름, {filename: 파일경로}]");
        System.out.println("  - 같은 이름으로 재등록 시 동일 오프셋을 이어받아 중복 없음");
        System.out.println("  - 커넥터 이름 변경 시: 새 오프셋 시작 → 처음부터 재처리");
        printSeparator();

        assertThat(firstBatch).hasSize(LINE_COUNT);
        assertThat(afterRestart).hasSize(LINE_COUNT); // 중복 없음 확인
    }

    /**
     * Q3. connect-offsets 토픽 — 오프셋이 어디에 어떻게 기록되는가?
     *
     * Connect는 파일 읽기 진행 상황을 connect-offsets 토픽에 JSON 형태로 저장한다.
     * 키: ["커넥터이름", {"filename":"경로"}]
     * 값: {"position": 바이트_오프셋}
     */
    @Test
    @Order(3)
    @DisplayName("Q3: connect-offsets 토픽에서 오프셋이 어떻게 기록되는지 확인한다")
    void connect_offsets_topic_contains_file_position() throws Exception {
        System.out.println("\n  Q3: connect-offsets 토픽 직접 조회");
        System.out.println("  → Q2에서 등록된 커넥터의 오프셋이 토픽에 기록되어 있어야 한다");

        var offsets = readConnectOffsets(CONNECTOR_NAME, 10_000);

        System.out.println();
        System.out.println("  connect-offsets 토픽 레코드 (커넥터 관련):");
        if (offsets.isEmpty()) {
            System.out.println("  [없음] 커넥터가 아직 오프셋을 커밋하지 않았습니다.");
        } else {
            offsets.forEach(r -> {
                System.out.println("  키 (byte decoded): " + r.key());
                System.out.println("  값: " + r.value());
            });
        }
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 키: [\"" + CONNECTOR_NAME + "\", {\"filename\":\"" + SOURCE_FILE + "\"}]");
        System.out.println("  - 값: {\"position\": N} — 파일 내 바이트 위치");
        System.out.println("  - 이 토픽이 존재하는 한 커넥터를 재시작해도 N 이후부터 읽음");
        System.out.println("  - 토픽은 compact 정책 → 커넥터당 최신 오프셋 1개만 유지");
        printSeparator();

        // connect-offsets 토픽 자체가 존재하고 레코드가 있어야 한다
        var allOffsets = consumeUntilTimeout("connect-offsets", "lab01-q3-" + System.nanoTime(), 5_000);
        assertThat(allOffsets).isNotEmpty();
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private void readFileAndProduce(Path file, String topic, String runLabel) throws Exception {
        List<String> lines = java.nio.file.Files.readAllLines(file);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");

        System.out.printf("  [%s] %d개 라인을 %s 토픽으로 전송%n", runLabel, lines.size(), topic);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (String line : lines) {
                producer.send(new ProducerRecord<>(topic, null, line)).get();
            }
            producer.flush();
        }
    }
}
