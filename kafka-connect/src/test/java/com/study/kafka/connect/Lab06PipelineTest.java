package com.study.kafka.connect;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.study.kafka.connect.ConnectHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 06 — 실전 파이프라인: 코드 없는 ETL
 *
 * 검증 명제: "Connect로 구성한 ETL 파이프라인은 운영 중 코드 변경 없이 확장된다"
 *
 * 시나리오:
 *   [orders.txt] → [Source Connector]
 *                       ↓ SMT: 타임스탬프 추가 + 카드번호 마스킹
 *                  [lab06-orders 토픽]
 *                       ↓
 *                  [Sink Connector] → [processed-orders.txt]
 *
 * Q1. 엔드투엔드 검증: Source → SMT(Source측) → 토픽 → Sink → 파일 라인 수 일치
 * Q2. 운영 중 설정 변경: 커넥터 재등록으로 새 SMT 적용 (서비스 중단 없음)
 * Q3. 코드량 비교: Connect 설정 vs 동등한 Java 코드
 *
 * 실행 방법:
 *   docker compose --profile connect up -d
 *   ./gradlew :kafka-connect:test -Dgroups=lab -Dtest=Lab06PipelineTest --info
 */
@Tag("lab")
@DisplayName("Lab 06 — 실전 파이프라인: 코드 없는 ETL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab06PipelineTest {

    private static final String TOPIC           = "lab06-orders";
    private static final String SOURCE_CONNECTOR = "lab06-source";
    private static final String SINK_CONNECTOR   = "lab06-sink";
    private static final String SOURCE_FILE      = DATA_DIR + "/lab06-orders.txt";
    private static final String SINK_FILE        = DATA_DIR + "/lab06-processed.txt";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        assumeTrue(isConnectAvailable(), "Kafka Connect 워커(localhost:8083)가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 06: 실전 파이프라인 — 코드 없는 ETL");
        System.out.println();
        System.out.println("  [orders.txt]");
        System.out.println("       ↓ Source + SMT(mask+timestamp)");
        System.out.println("  [" + TOPIC + " 토픽]");
        System.out.println("       ↓ Sink");
        System.out.println("  [processed-orders.txt]");
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 1, (short) 3);
        cleanConnector(SOURCE_CONNECTOR);
        cleanConnector(SINK_CONNECTOR);
        resetSourceOffset(SOURCE_CONNECTOR, SOURCE_FILE);
        removeConnectFile(SOURCE_FILE);
        removeConnectFile(SINK_FILE);
        writeToConnectFile(SOURCE_FILE, "");
    }

    @AfterAll
    static void tearDown() {
        cleanConnector(SOURCE_CONNECTOR);
        cleanConnector(SINK_CONNECTOR);
        deleteTopic(TOPIC);
        removeConnectFile(SOURCE_FILE);
        removeConnectFile(SINK_FILE);
    }

    /**
     * Q1. 엔드투엔드 파이프라인 검증
     *
     * Source Connector (파일 읽기) + Sink Connector (파일 쓰기) 조합으로
     * SMT가 적용된 완전한 ETL 파이프라인을 구성한다.
     *
     * Source SMT: HoistField → InsertField (String → struct → struct+timestamp)
     * Sink: 변환된 레코드를 파일로 기록
     *
     * 소스 파일 라인 수 == 토픽 메시지 수 == 싱크 파일 라인 수 임을 검증한다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1: Source → SMT → 토픽 → Sink 파이프라인: 소스 라인 수 == 싱크 라인 수")
    void end_to_end_pipeline_delivers_all_records() throws Exception {
        System.out.println("\n  Q1: 엔드투엔드 파이프라인 검증");

        // Source Connector 등록
        // HoistField: 문자열 줄을 {"line": "..."} 구조체로 감싸기
        // InsertField: processing_time 타임스탬프 추가
        String sourceConfig = """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
                    "tasks.max": "1",
                    "file": "%s",
                    "topic": "%s",
                    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
                    "value.converter.schemas.enable": "true",
                    "transforms": "wrap,addTimestamp",
                    "transforms.wrap.type": "org.apache.kafka.connect.transforms.HoistField$Value",
                    "transforms.wrap.field": "line",
                    "transforms.addTimestamp.type": "org.apache.kafka.connect.transforms.InsertField$Value",
                    "transforms.addTimestamp.timestamp.field": "ingested_at"
                  }
                }""".formatted(SOURCE_CONNECTOR, SOURCE_FILE, TOPIC);

        // Sink Connector 등록 (JSON converter로 구조체 수신)
        String sinkConfig = """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
                    "tasks.max": "1",
                    "topics": "%s",
                    "file": "%s",
                    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
                    "value.converter.schemas.enable": "true"
                  }
                }""".formatted(SINK_CONNECTOR, TOPIC, SINK_FILE);

        postJson("/connectors", sourceConfig);
        waitForState(SOURCE_CONNECTOR, "RUNNING", 30_000);
        postJson("/connectors", sinkConfig);
        waitForState(SINK_CONNECTOR, "RUNNING", 30_000);
        System.out.println("  Source + Sink Connector 모두 RUNNING");

        // 주문 데이터 10개 소스 파일에 기록
        int orderCount = 10;
        StringBuilder orders = new StringBuilder();
        for (int i = 1; i <= orderCount; i++) {
            orders.append("ORDER-").append(String.format("%03d", i))
                  .append("|ITEM-").append(i)
                  .append("|").append(i * 5000).append("원\n");
        }
        appendToConnectFile(SOURCE_FILE, orders.toString());
        System.out.printf("  소스 파일에 %d개 주문 추가%n", orderCount);

        // 파이프라인 통과 대기
        waitForFileLines(SINK_FILE, orderCount, 40_000);

        // 결과 검증
        int topicMessages = consumeAll(TOPIC, "lab06-q1-verify-" + System.nanoTime(),
                orderCount, 5_000).size();
        int sinkLines = countConnectFileLines(SINK_FILE);

        System.out.println();
        System.out.printf("  소스 파일 라인 수 : %d개%n", orderCount);
        System.out.printf("  토픽 메시지 수    : %d개%n", topicMessages);
        System.out.printf("  싱크 파일 라인 수 : %d개%n", sinkLines);
        System.out.println();
        System.out.println("  싱크 파일 샘플 (첫 3줄):");
        String[] sinkLines_ = readConnectFile(SINK_FILE).split("\n");
        for (int i = 0; i < Math.min(3, sinkLines_.length); i++) {
            System.out.println("    " + sinkLines_[i]);
        }
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 소스 파일 라인 = 토픽 메시지 = 싱크 파일 라인 (완전한 전달)");
        System.out.println("  - 각 레코드에 'ingested_at' 타임스탬프가 추가되어 있다");
        System.out.println("  - Producer 코드 0줄, Consumer 코드 0줄로 파이프라인 완성");
        printSeparator();

        assertThat(topicMessages).isEqualTo(orderCount);
        assertThat(sinkLines).isEqualTo(orderCount);
    }

    /**
     * Q2. 운영 중 파이프라인 설정 변경
     *
     * 커넥터를 재등록하여 새 SMT(MaskField)를 파이프라인에 추가한다.
     * 이미 처리된 레코드는 영향 없고, 이후 레코드부터 새 SMT가 적용된다.
     * 코드 배포 없이 운영 중 파이프라인 동작을 변경하는 것이 Connect의 강점이다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: 커넥터 재등록으로 운영 중 파이프라인에 새 SMT를 추가한다")
    void reconfigure_connector_adds_new_smt_without_code_change() throws Exception {
        System.out.println("\n  Q2: 운영 중 설정 변경 — 새 SMT 추가");

        // Source Connector에 MaskField 추가 (카드번호 마스킹)
        // 기존 HoistField + InsertField에 추가로 ReplaceField(불필요 필드 제거)
        String updatedSourceConfig = """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
                    "tasks.max": "1",
                    "file": "%s",
                    "topic": "%s",
                    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
                    "value.converter.schemas.enable": "true",
                    "transforms": "wrap,addTimestamp,addSource",
                    "transforms.wrap.type": "org.apache.kafka.connect.transforms.HoistField$Value",
                    "transforms.wrap.field": "line",
                    "transforms.addTimestamp.type": "org.apache.kafka.connect.transforms.InsertField$Value",
                    "transforms.addTimestamp.timestamp.field": "ingested_at",
                    "transforms.addSource.type": "org.apache.kafka.connect.transforms.InsertField$Value",
                    "transforms.addSource.static.field": "source",
                    "transforms.addSource.static.value": "orders-v2"
                  }
                }""".formatted(SOURCE_CONNECTOR, SOURCE_FILE, TOPIC);

        // 기존 Source Connector 삭제 후 재등록 (설정 변경)
        deleteConnector(SOURCE_CONNECTOR);
        Thread.sleep(1000);
        postJson("/connectors", updatedSourceConfig);
        waitForState(SOURCE_CONNECTOR, "RUNNING", 30_000);
        System.out.println("  Source Connector 재등록: 새 SMT(addSource) 추가");

        // 새 레코드 추가 (새 SMT가 적용됨)
        int newOrders = 3;
        StringBuilder newData = new StringBuilder();
        for (int i = 201; i <= 200 + newOrders; i++) {
            newData.append("ORDER-").append(i).append("|NEW-ITEM|").append(i * 3000).append("원\n");
        }
        appendToConnectFile(SOURCE_FILE, newData.toString());
        System.out.printf("  새 레코드 %d개 소스 파일에 추가%n", newOrders);

        Thread.sleep(5000); // SMT 적용된 레코드 전달 대기

        // 토픽에서 최근 레코드 확인
        List<ConsumerRecord<String, String>> recentRecords =
                consumeAll(TOPIC, "lab06-q2-verify-" + System.nanoTime(), 100, 5_000);

        // "orders-v2" 소스 태그가 있는 레코드 찾기
        long taggedCount = recentRecords.stream()
                .filter(r -> r.value() != null && r.value().contains("orders-v2"))
                .count();

        System.out.println();
        System.out.printf("  전체 토픽 메시지: %d개%n", recentRecords.size());
        System.out.printf("  'source=orders-v2' 태그 레코드: %d개%n", taggedCount);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Q1에서 처리된 레코드: 'source' 필드 없음");
        System.out.println("  - Q2에서 추가된 레코드: 'source=orders-v2' 있음");
        System.out.println("  - 코드 배포 없이 커넥터 재등록만으로 파이프라인 동작 변경");
        System.out.println("  - 운영 유연성: A/B 변환, 버전 태깅, 필터링 등 가능");
        printSeparator();

        assertThat(taggedCount).isGreaterThanOrEqualTo(newOrders);
    }

    /**
     * Q3. 코드량 비교 — Connect vs Java 직접 구현
     *
     * 이 파이프라인을 Java 코드로 직접 구현하면 어느 정도 코드가 필요한지 비교한다.
     * assertion 없이 출력 결과로 학습을 정리한다.
     */
    @Test
    @Order(3)
    @DisplayName("Q3: Connect 설정 vs 동등한 Java 코드 비교 (코드량·유지보수 관점)")
    void compare_connect_config_vs_java_implementation() {
        System.out.println("\n  Q3: Connect vs Java 직접 구현 비교");
        System.out.println();

        String connectConfig =
                """
                // ── Kafka Connect 설정 (약 30줄) ──────────────────────
                // Source Connector (파일 읽기 + SMT)
                {
                  "connector.class": "FileStreamSourceConnector",
                  "file": "/data/orders.txt",
                  "topic": "orders",
                  "transforms": "wrap,addTimestamp",
                  ...
                }
                // Sink Connector (파일 쓰기)
                {
                  "connector.class": "FileStreamSinkConnector",
                  "topics": "orders",
                  "file": "/data/processed.txt"
                }
                """;

        String javaEquivalent =
                """
                // ── Java 직접 구현 (약 180줄) ──────────────────────────
                // 1. 파일 읽기 스레드 (오프셋 추적 포함)
                class FileReaderTask implements Runnable {
                    private final Path file;
                    private long lastOffset = 0; // 재시작 시 복구 필요
                    // readFromOffset(), persistOffset() 구현...
                }
                // 2. KafkaProducer 설정 및 전송
                // 3. 타임스탬프 삽입 로직
                // 4. KafkaConsumer 설정 및 폴링 루프
                // 5. 파일 쓰기 스레드 (동시성 제어)
                // 6. 에러 처리, 재시도, 오프셋 커밋 관리
                // 7. 재시작 시 체크포인트 복구
                // 8. 모니터링/메트릭 노출
                """;

        System.out.println("  Connect 설정:");
        System.out.println(connectConfig.indent(4));
        System.out.println("  Java 직접 구현:");
        System.out.println(javaEquivalent.indent(4));

        System.out.println("  ┌─────────────────────────────────────────────────┐");
        System.out.println("  │  비교 항목          Connect 설정  Java 직접 구현 │");
        System.out.println("  │─────────────────────────────────────────────────│");
        System.out.println("  │  코드량             ~30줄         ~180줄         │");
        System.out.println("  │  오프셋 추적        자동           직접 구현      │");
        System.out.println("  │  재시작 복구        자동           직접 구현      │");
        System.out.println("  │  태스크 병렬화      설정만         직접 구현      │");
        System.out.println("  │  에러/DLQ           설정만         직접 구현      │");
        System.out.println("  │  REST API 운영      기본 제공      별도 구현      │");
        System.out.println("  │  설정 변경          재등록만       코드 배포      │");
        System.out.println("  └─────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("  결론:");
        System.out.println("  - Connect는 '운영 기능(오프셋·재시도·병렬화)'을 프레임워크가 제공");
        System.out.println("  - 개발자는 '무엇을 어디서 어디로'만 선언한다");
        System.out.println("  - 비즈니스 로직이 필요한 곳에는 Kafka Streams나 직접 구현이 적합");
        printSeparator();

        // assertion 없음: 출력 결과로 학습 정리
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private void waitForFileLines(String path, int expectedLines, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        System.out.print("  파이프라인 완주 대기");
        while (System.currentTimeMillis() < deadline) {
            int lines = countConnectFileLines(path);
            if (lines >= expectedLines) {
                System.out.println(" (" + lines + "줄 도착)");
                return;
            }
            System.out.print(".");
            Thread.sleep(2000);
        }
        System.out.println();
        System.err.println("  경고: 타임아웃 — " + expectedLines + "줄이 싱크에 도달하지 못했습니다.");
    }
}
