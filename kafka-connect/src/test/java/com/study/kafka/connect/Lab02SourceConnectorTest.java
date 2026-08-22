package com.study.kafka.connect;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.study.kafka.connect.ConnectHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 02 — Source Connector: 파일 → 카프카
 *
 * 검증 명제: "Source Connector는 외부 데이터를 Kafka로 이동하는 자율 에이전트다"
 *
 * Q1. FileStream Source 등록 → 파일에 주문 데이터 추가 → 토픽 수신 확인
 * Q2. 커넥터 PAUSE → 파일 추가 → RESUME → 밀린 메시지 모두 수신 (유실 없음)
 * Q3. tasks.max=3 설정 시 실제 task 수 확인 (FileStream 제한: tasks.max=1)
 *
 * 실행 방법:
 *   docker compose --profile connect up -d
 *   ./gradlew :kafka-connect:test -Dgroups=lab -Dtest=Lab02SourceConnectorTest --info
 */
@Tag("lab")
@DisplayName("Lab 02 — Source Connector: 파일 → 카프카")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab02SourceConnectorTest {

    private static final String TOPIC          = "lab02-orders";
    private static final String CONNECTOR_NAME = "lab02-source";
    private static final String SOURCE_FILE    = DATA_DIR + "/lab02-orders.txt";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        assumeTrue(isConnectAvailable(), "Kafka Connect 워커(localhost:8083)가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 02: Source Connector — 파일 → 카프카 (9.4)");
        System.out.println("  토픽: " + TOPIC);
        System.out.println("  소스: " + SOURCE_FILE);
        System.out.println("=".repeat(62));
        createTopic(TOPIC, 1, (short) 3);
        cleanConnector(CONNECTOR_NAME);
        resetSourceOffset(CONNECTOR_NAME, SOURCE_FILE);
        removeConnectFile(SOURCE_FILE);
        // 빈 파일 생성 (FileStream 커넥터는 파일이 존재해야 시작됨)
        writeToConnectFile(SOURCE_FILE, "");
    }

    @AfterAll
    static void tearDown() {
        cleanConnector(CONNECTOR_NAME);
        deleteTopic(TOPIC);
        removeConnectFile(SOURCE_FILE);
    }

    /**
     * Q1. 기본 파이프라인: 파일 → Source Connector → 토픽
     *
     * FileStream Source Connector는 파일을 tail하며 새 줄이 추가될 때마다
     * 해당 줄을 레코드로 만들어 Kafka 토픽에 전송한다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1: 파일에 추가된 데이터가 Source Connector를 통해 토픽으로 전송된다")
    void source_connector_delivers_file_lines_to_topic() throws Exception {
        System.out.println("\n  Q1: 기본 Source Connector 파이프라인");
        System.out.println("  파일 → [FileStream Source] → " + TOPIC);

        // 커넥터 등록
        postJson("/connectors", buildSourceConfig(CONNECTOR_NAME, SOURCE_FILE, TOPIC, 1));
        waitForState(CONNECTOR_NAME, "RUNNING", 30_000);
        System.out.println("  커넥터 등록 및 RUNNING 상태 확인");

        // 주문 데이터 5개 파일에 추가
        int writeCount = 5;
        StringBuilder data = new StringBuilder();
        for (int i = 1; i <= writeCount; i++) {
            data.append("{\"orderId\":\"ORD-").append(String.format("%03d", i))
                .append("\",\"item\":\"product-").append(i)
                .append("\",\"amount\":").append(i * 1000).append("}\n");
        }
        appendToConnectFile(SOURCE_FILE, data.toString());
        System.out.printf("  파일에 %d개 주문 추가%n", writeCount);

        // 토픽에서 수신
        var records = consumeAll(TOPIC, "lab02-q1-" + System.nanoTime(), writeCount, 20_000);

        System.out.println();
        System.out.println("  수신된 레코드:");
        records.forEach(r -> System.out.println("    [offset=" + r.offset() + "] " + r.value()));
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Connect가 파일을 폴링(기본 500ms 간격)하며 새 줄을 감지한다");
        System.out.println("  - 각 줄 = 하나의 Kafka 레코드 (키 없음, 값 = 줄 전체)");
        System.out.println("  - 커넥터 설정만으로 프로듀서 코드 없이 파이프라인 완성");
        printSeparator();

        assertThat(records).hasSize(writeCount);
        assertThat(records).allMatch(r -> r.value().contains("orderId"));
    }

    /**
     * Q2. PAUSE / RESUME — 데이터 유실 없는 일시정지
     *
     * PAUSE 상태에서 파일에 데이터를 추가해도 Kafka 전송이 멈춘다.
     * RESUME 시 밀린 데이터가 모두 전송된다 (유실 없음).
     * 이것은 운영 중 무중단 설정 변경이나 다운스트림 장애 시 유용하다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: PAUSE 중 파일에 추가된 데이터는 RESUME 후 전부 전송된다 (유실 없음)")
    void pause_resume_delivers_all_buffered_data() throws Exception {
        System.out.println("\n  Q2: PAUSE / RESUME — 데이터 유실 없음");

        // Q1에서 커넥터가 이미 RUNNING, 파일 오프셋이 기록되어 있음
        // 커넥터를 일시정지
        pauseConnector(CONNECTOR_NAME);
        Thread.sleep(1000);
        System.out.println("  커넥터 PAUSE 완료");

        // PAUSE 중 파일에 데이터 추가 (이 데이터는 아직 전송되지 않음)
        int pausedCount = 3;
        StringBuilder pausedData = new StringBuilder();
        for (int i = 101; i <= 100 + pausedCount; i++) {
            pausedData.append("{\"orderId\":\"ORD-").append(i)
                      .append("\",\"item\":\"paused-product\",\"amount\":").append(i * 500).append("}\n");
        }
        appendToConnectFile(SOURCE_FILE, pausedData.toString());
        System.out.printf("  PAUSE 중 %d개 주문 파일에 추가 (전송 보류)%n", pausedCount);

        // PAUSE 상태에서 잠시 대기 → 토픽에 새 메시지 없어야 함 (latest 오프셋부터 읽어 기존 메시지 제외)
        Thread.sleep(3000);
        var duringPause = consumeNew(TOPIC, "lab02-q2-during-pause-" + System.nanoTime(), 1, 3_000);
        System.out.printf("  PAUSE 중 새로 수신된 메시지: %d개 (0이어야 함)%n", duringPause.size());

        // consumer를 현재 끝(Q1 5건 이후)에 위치시키고, RESUME 후 새 메시지만 수신
        // seekToEnd → action(resume) → poll: race condition 없이 정확히 3건만 읽음
        var afterResume = consumeAfterAction(TOPIC, () -> {
            try {
                resumeConnector(CONNECTOR_NAME);
                System.out.println("  커넥터 RESUME — 밀린 데이터 전송 시작");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, pausedCount, 15_000);

        System.out.printf("  RESUME 후 수신 메시지: %d개%n", afterResume.size());
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - PAUSE: 커넥터 태스크가 파일 폴링을 중단한다 (파일은 계속 추가 가능)");
        System.out.println("  - RESUME: 마지막 오프셋부터 이어서 전송, 데이터 유실 없음");
        System.out.println("  - 활용: 다운스트림 장애 시 커넥터를 PAUSE해 압력을 줄인 후 RESUME");
        printSeparator();

        assertThat(duringPause).isEmpty();
        assertThat(afterResume).hasSize(pausedCount);
    }

    /**
     * Q3. tasks.max 제한 — FileStream Connector는 왜 task가 1개인가?
     *
     * tasks.max=3으로 설정해도 FileStream Source는 실제로 1개 task만 생성한다.
     * task 수는 커넥터 구현(파티션 개수)에 의존하기 때문이다.
     * FileStream은 단일 파일 = 단일 파티션이므로 병렬화 불가능.
     */
    @Test
    @Order(3)
    @DisplayName("Q3: tasks.max=3 설정 시 FileStream Connector는 실제 task 1개만 생성한다")
    void filestream_connector_ignores_tasks_max_greater_than_one() throws Exception {
        System.out.println("\n  Q3: tasks.max 제한 이해");

        // tasks.max=3 으로 새 커넥터 등록
        String multiTaskConnector = CONNECTOR_NAME + "-multi";
        String sourceFile2 = DATA_DIR + "/lab02-multi.txt";
        writeToConnectFile(sourceFile2, "line1\nline2\nline3\n");

        postJson("/connectors", buildSourceConfig(multiTaskConnector, sourceFile2, TOPIC, 3));
        waitForState(multiTaskConnector, "RUNNING", 30_000);

        int actualTasks = getTaskCount(multiTaskConnector);
        String tasksDetail = getJson("/connectors/" + multiTaskConnector + "/tasks");

        System.out.println();
        System.out.printf("  설정: tasks.max=3%n");
        System.out.printf("  실제 task 수: %d개%n", actualTasks);
        System.out.println("  task 목록: " + tasksDetail.substring(0, Math.min(200, tasksDetail.length())));
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - Connector.taskConfigs()가 실제 task 수를 결정한다");
        System.out.println("  - FileStream은 파일 1개 = 파티션 1개 → 항상 task 1개");
        System.out.println("  - tasks.max는 상한선: min(tasks.max, connector_partitions) 적용");
        System.out.println("  - 병렬화가 필요하면: 여러 파일 → 여러 커넥터 인스턴스 또는 JDBCSource처럼 파티션 지원 커넥터 사용");
        printSeparator();

        deleteConnector(multiTaskConnector);
        removeConnectFile(sourceFile2);

        assertThat(actualTasks).isEqualTo(1);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private static String buildSourceConfig(String name, String file, String topic, int tasksMax) {
        return """
                {
                  "name": "%s",
                  "config": {
                    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
                    "tasks.max": "%d",
                    "file": "%s",
                    "topic": "%s"
                  }
                }""".formatted(name, tasksMax, file, topic);
    }
}
