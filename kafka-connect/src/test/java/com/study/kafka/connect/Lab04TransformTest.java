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
 * Lab 04 — SMT(Single Message Transforms): 코드 없는 변환
 *
 * 검증 명제: "SMT는 파이프라인 중간에서 코드 변경 없이 데이터를 변환한다"
 *
 * 시나리오: 주문 토픽에 구조화된 JSON 레코드가 들어온다.
 *          Sink Connector + SMT로 코드 변경 없이 데이터를 변환하여 파일에 기록한다.
 *
 * Q1. InsertField — 처리 타임스탬프를 자동으로 추가한다
 * Q2. MaskField — 카드번호 필드를 빈 값으로 마스킹한다
 * Q3. SMT 체이닝 — InsertField + MaskField + ReplaceField를 순서대로 적용한다
 *
 * 실행 방법:
 *   docker compose --profile connect up -d
 *   ./gradlew :kafka-connect:test -Dgroups=lab -Dtest=Lab04TransformTest --info
 *
 * 참고: SMT는 Sink Connector에서 value.converter=JsonConverter로 구조화 레코드를 받아야
 *       InsertField/MaskField/ReplaceField 등 필드 수준 변환이 가능하다.
 */
@Tag("lab")
@DisplayName("Lab 04 — SMT: 코드 없는 변환")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab04TransformTest {

    private static final String TOPIC_Q1        = "lab04-orders-q1";
    private static final String TOPIC_Q2        = "lab04-orders-q2";
    private static final String TOPIC_Q3        = "lab04-orders-q3";
    private static final String CONNECTOR_Q1    = "lab04-sink-q1";
    private static final String CONNECTOR_Q2    = "lab04-sink-q2";
    private static final String CONNECTOR_Q3    = "lab04-sink-q3";
    private static final String SINK_FILE_Q1    = DATA_DIR + "/lab04-output-q1.txt";
    private static final String SINK_FILE_Q2    = DATA_DIR + "/lab04-output-q2.txt";
    private static final String SINK_FILE_Q3    = DATA_DIR + "/lab04-output-q3.txt";

    // 샘플 주문 레코드 (JSON)
    private static final String ORDER_JSON =
            "{\"orderId\":\"ORD-001\",\"cardNumber\":\"4111-1111-1111-1111\"," +
            "\"amount\":99900,\"internalId\":\"INT-9999\",\"status\":\"PENDING\"}";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        assumeTrue(isConnectAvailable(), "Kafka Connect 워커(localhost:8083)가 실행되지 않아 실습을 건너뜁니다.");
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  Lab 04: SMT — 코드 없는 변환 (9.5)");
        System.out.println("  핵심: Connector 설정만으로 데이터를 가공한다");
        System.out.println("=".repeat(62));
        createTopic(TOPIC_Q1, 1, (short) 3);
        createTopic(TOPIC_Q2, 1, (short) 3);
        createTopic(TOPIC_Q3, 1, (short) 3);
        cleanConnector(CONNECTOR_Q1);
        cleanConnector(CONNECTOR_Q2);
        cleanConnector(CONNECTOR_Q3);
        removeConnectFile(SINK_FILE_Q1);
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
        removeConnectFile(SINK_FILE_Q1);
        removeConnectFile(SINK_FILE_Q2);
        removeConnectFile(SINK_FILE_Q3);
    }

    /**
     * Q1. InsertField — 처리 타임스탬프 자동 추가
     *
     * 원본 JSON에 없는 "processing_time" 필드를 커넥터 설정만으로 추가한다.
     * 비즈니스 로직이나 Producer 코드를 변경할 필요가 없다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1: InsertField SMT가 처리 타임스탬프를 자동으로 추가한다")
    void insert_field_adds_processing_timestamp() throws Exception {
        System.out.println("\n  Q1: InsertField — 타임스탬프 자동 삽입");
        System.out.println("  입력: " + ORDER_JSON);
        System.out.println("  변환: + processing_time (커넥터 처리 시각)");

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
                    "transforms": "addTimestamp",
                    "transforms.addTimestamp.type": "org.apache.kafka.connect.transforms.InsertField$Value",
                    "transforms.addTimestamp.timestamp.field": "processing_time"
                  }
                }""".formatted(CONNECTOR_Q1, TOPIC_Q1, SINK_FILE_Q1);

        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_Q1, "RUNNING", 30_000);
        System.out.println("  Sink Connector (InsertField) 등록: RUNNING");

        // 주문 레코드 전송
        sendJsonMessage(TOPIC_Q1, ORDER_JSON);
        System.out.println("  주문 레코드 전송 완료");

        // 파일에 기록될 때까지 대기
        waitForFileContent(SINK_FILE_Q1, "processing_time", 15_000);

        String output = readConnectFile(SINK_FILE_Q1);
        System.out.println();
        System.out.println("  파일 출력:");
        System.out.println("    " + output);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 원본에 없던 'processing_time' 필드가 추가되었다");
        System.out.println("  - 값은 Connector가 레코드를 처리한 시각(Unix timestamp ms)");
        System.out.println("  - Producer 코드 변경 없음 — Connector 설정 변경만으로 적용");
        System.out.println("  - 활용: 처리 지연 추적, SLA 모니터링용 메타데이터 추가");
        printSeparator();

        assertThat(output).contains("processing_time");
        assertThat(output).contains("ORD-001");
    }

    /**
     * Q2. MaskField — 민감 정보 마스킹
     *
     * 카드번호처럼 민감한 필드를 Connector 설정으로 빈 값으로 대체한다.
     * 데이터가 싱크에 도달하기 전 중간에서 차단되는 구조다.
     * 실무에서는 GDPR/PCI-DSS 준수를 위해 자주 사용된다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2: MaskField SMT가 카드번호 필드를 마스킹한다 (원본 값이 싱크에 도달하지 않음)")
    void mask_field_removes_sensitive_card_number() throws Exception {
        System.out.println("\n  Q2: MaskField — 카드번호 마스킹");
        System.out.println("  입력: cardNumber=" + "4111-1111-1111-1111");
        System.out.println("  변환: cardNumber → 빈 값(마스킹됨)");

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
                    "transforms": "maskCard",
                    "transforms.maskCard.type": "org.apache.kafka.connect.transforms.MaskField$Value",
                    "transforms.maskCard.fields": "cardNumber"
                  }
                }""".formatted(CONNECTOR_Q2, TOPIC_Q2, SINK_FILE_Q2);

        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_Q2, "RUNNING", 30_000);
        System.out.println("  Sink Connector (MaskField) 등록: RUNNING");

        sendJsonMessage(TOPIC_Q2, ORDER_JSON);
        System.out.println("  주문 레코드 전송 완료 (카드번호 포함)");

        // 카드번호 원본이 파일에 없을 때까지 대기
        waitForFileContent(SINK_FILE_Q2, "ORD-001", 15_000);

        String output = readConnectFile(SINK_FILE_Q2);
        System.out.println();
        System.out.println("  파일 출력:");
        System.out.println("    " + output);
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 'cardNumber' 필드가 빈 문자열로 대체되었다");
        System.out.println("  - 원본 카드번호(4111-1111-1111-1111)가 싱크에 도달하지 않음");
        System.out.println("  - MaskField: string → \"\", number → 0, boolean → false");
        System.out.println("  - 패턴 마스킹(****-****-****-1111)이 필요하면 커스텀 SMT 사용");
        printSeparator();

        assertThat(output).contains("ORD-001");
        assertThat(output).doesNotContain("4111-1111-1111-1111");
    }

    /**
     * Q3. SMT 체이닝 — 여러 변환을 순서대로 적용
     *
     * transforms=addTimestamp,maskCard,dropInternal 순으로 3개 SMT를 체이닝한다.
     * 각 SMT는 이전 SMT의 출력을 입력으로 받아 순차 변환한다.
     * 코드 한 줄 없이 비즈니스 변환 파이프라인을 구성한다.
     */
    @Test
    @Order(3)
    @DisplayName("Q3: SMT 체이닝 — InsertField + MaskField + ReplaceField 순차 적용")
    void chained_smts_apply_multiple_transforms_in_order() throws Exception {
        System.out.println("\n  Q3: SMT 체이닝");
        System.out.println("  입력: " + ORDER_JSON);
        System.out.println("  변환: [+processing_time] → [mask cardNumber] → [drop internalId]");

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
                    "transforms": "addTimestamp,maskCard,dropInternal",
                    "transforms.addTimestamp.type": "org.apache.kafka.connect.transforms.InsertField$Value",
                    "transforms.addTimestamp.timestamp.field": "processing_time",
                    "transforms.maskCard.type": "org.apache.kafka.connect.transforms.MaskField$Value",
                    "transforms.maskCard.fields": "cardNumber",
                    "transforms.dropInternal.type": "org.apache.kafka.connect.transforms.ReplaceField$Value",
                    "transforms.dropInternal.exclude": "internalId"
                  }
                }""".formatted(CONNECTOR_Q3, TOPIC_Q3, SINK_FILE_Q3);

        postJson("/connectors", connectorConfig);
        waitForState(CONNECTOR_Q3, "RUNNING", 30_000);
        System.out.println("  Sink Connector (3개 SMT 체인) 등록: RUNNING");

        sendJsonMessage(TOPIC_Q3, ORDER_JSON);

        waitForFileContent(SINK_FILE_Q3, "ORD-001", 15_000);
        String output = readConnectFile(SINK_FILE_Q3);

        System.out.println();
        System.out.println("  원본 레코드:");
        System.out.println("    orderId=ORD-001, cardNumber=4111-..., amount=99900");
        System.out.println("    internalId=INT-9999, status=PENDING");
        System.out.println();
        System.out.println("  변환 후 출력:");
        System.out.println("    " + output);
        System.out.println();

        boolean hasTimestamp  = output.contains("processing_time");
        boolean cardMasked    = !output.contains("4111-1111-1111-1111");
        boolean internalDropped = !output.contains("INT-9999");

        System.out.printf("  ✓ processing_time 추가: %s%n", hasTimestamp ? "YES" : "NO");
        System.out.printf("  ✓ cardNumber 마스킹: %s%n", cardMasked ? "YES" : "NO");
        System.out.printf("  ✓ internalId 제거: %s%n", internalDropped ? "YES" : "NO");
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 3개 SMT가 선언된 순서대로 순차 실행된다");
        System.out.println("  - SMT 추가/제거는 커넥터 재등록만으로 적용 (코드 배포 없음)");
        System.out.println("  - 체인 길이에 제한 없음; 단, 순서에 의존하는 변환에 주의");
        printSeparator();

        assertThat(hasTimestamp).isTrue();
        assertThat(cardMasked).isTrue();
        assertThat(internalDropped).isTrue();
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────

    private void sendJsonMessage(String topic, String json) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");

        CountDownLatch latch = new CountDownLatch(1);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, null, json), (m, e) -> latch.countDown());
            producer.flush();
        }
        latch.await(10, TimeUnit.SECONDS);
    }

    private void waitForFileContent(String path, String marker, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        System.out.print("  변환 완료 대기");
        while (System.currentTimeMillis() < deadline) {
            String content = readConnectFile(path);
            if (content.contains(marker)) {
                System.out.println(" 완료");
                return;
            }
            System.out.print(".");
            Thread.sleep(1000);
        }
        System.out.println();
        System.err.println("  경고: 타임아웃 — 파일에서 '" + marker + "'를 찾지 못했습니다.");
    }
}
