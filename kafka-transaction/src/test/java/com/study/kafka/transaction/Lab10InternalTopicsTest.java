package com.study.kafka.transaction;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 10 — 내부 토픽 직접 관찰
 *
 * 검증 명제: "트랜잭션 상태와 오프셋 커밋은 마법이 아니라 평범한 Kafka 토픽에 쌓이는 레코드다"
 *
 * Lab 03에서 "오프셋 커밋도 결국 __consumer_offsets 토픽에 대한 쓰기이기 때문에
 * 같은 트랜잭션에 묶을 수 있다"고 말했다. Lab 10은 그 문장을 말이 아니라 눈으로 확인한다.
 * Lab 01~08이 트랜잭션을 '바깥에서'(보인다/안 보인다, 오프셋이 전진했다/아니다) 관찰했다면,
 * 여기서는 브로커가 상태를 적어 두는 내부 로그를 직접 열어 본다.
 *
 * 내부 토픽이라고 해서 특별한 저장소가 아니다. __transaction_state 도 __consumer_offsets 도
 * 파티션 50개짜리 compact 토픽일 뿐이고, 일반 Consumer로 그냥 읽을 수 있다.
 * 레코드 값(value)은 브로커 내부 스키마로 직렬화되어 있어 사람이 읽기 어렵지만,
 * 키(key)는 "버전 헤더 + 문자열 필드" 구조라서 transactional.id / group.id가 ASCII 그대로 박혀 있다.
 *
 * Q1. 고유한 transactional.id로 트랜잭션을 한 번 수행한 뒤 __transaction_state 를
 *     ByteArrayDeserializer로 원시 바이트째 읽는다. 키 바이트 안에 그 transactional.id 문자열이
 *     들어 있는 레코드가 실제로 존재하는가? (= 트랜잭션 상태는 토픽 레코드다)
 * Q2. 같은 방식으로 __consumer_offsets 를 읽는다. sendOffsetsToTransaction()으로 오프셋을
 *     트랜잭션에 태워 커밋한 뒤, 키 바이트 안에 그 consumer group id가 들어 있는 레코드가 있는가?
 *     (= 오프셋 커밋이 '토픽 쓰기'라는 것의 직접 증거. 그래서 send와 같은 트랜잭션에 묶을 수 있다)
 * Q3. 사람이 읽을 수 있는 형태로도 덤프한다. Kafka 컨테이너 안에서 kafka-console-consumer.sh 를
 *     --formatter 와 함께 실행해 __transaction_state 를 출력한다.
 *     포매터 클래스 이름은 브로커 버전에 따라 다르므로 여러 후보를 순서대로 시도한다.
 *     ※ Q3은 출력 전용이며 단정하지 않는다 — 포매터 가용성은 버전 세부사항이기 때문이다.
 *
 * 주의: 이 실습은 내부 토픽을 '읽기만' 한다. 삭제하거나 설정을 바꾸지 않는다.
 *      내부 레코드 스키마도 파싱하지 않는다(버전에 취약하다). 바이트 검색으로 충분하다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab10*' --info
 */
@Tag("lab")
@DisplayName("Lab 10 — 내부 토픽 직접 관찰")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab10InternalTopicsTest {

    /** 오프셋 커밋이 기록되는 내부 토픽. (__transaction_state 는 TxHelper.TX_STATE_TOPIC 로 이미 있다) */
    private static final String CONSUMER_OFFSETS_TOPIC = "__consumer_offsets";

    private static final String IN_TOPIC  = "tx-lab10-in";
    private static final String OUT_TOPIC = "tx-lab10-out";
    private static final List<String> LAB_TOPICS = List.of(IN_TOPIC, OUT_TOPIC);

    /**
     * 내부 토픽은 이 저장소의 다른 실습(Lab 01~08)이 남긴 흔적도 함께 담고 있다.
     * "찾았다"가 이 테스트가 만든 레코드라는 것을 확실히 하려면 id가 절대 겹치지 않아야 한다.
     * @BeforeAll에서 한 번 만들어 Q1/Q2가 공유한다.
     */
    private static String txId;
    private static String groupId;

    /** 내부 토픽은 파티션이 50개라 스캔이 오래 걸릴 수 있다. 넉넉히 15초까지 허용한다. */
    private static final long SCAN_TIMEOUT_MS = 15_000;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 10: 내부 토픽 직접 관찰",
                "트랜잭션 상태와 오프셋 커밋은 평범한 토픽에 쌓이는 레코드인가?");

        long stamp = System.nanoTime();
        txId    = "tx-lab10-" + stamp;
        groupId = "tx-lab10-group-" + stamp;

        System.out.printf("  이번 실행의 transactional.id : %s%n", txId);
        System.out.printf("  이번 실행의 group.id         : %s%n", groupId);
        printSeparator();

        for (String topic : LAB_TOPICS) {
            createTopic(topic, 1, (short) 3);
        }
    }

    /** 실습용 토픽만 지운다. 내부 토픽(__로 시작)은 절대 건드리지 않는다. */
    @AfterAll
    static void tearDown() {
        for (String topic : LAB_TOPICS) {
            deleteTopic(topic);
        }
    }

    /**
     * Q1. __transaction_state 에 내 transactional.id가 적혀 있는가?
     *
     * 트랜잭션 코디네이터는 initTransactions / beginTransaction / commitTransaction 을 거치며
     * 해당 transactional.id의 상태(Empty → Ongoing → PrepareCommit → CompleteCommit)와
     * producerId·epoch·참여 파티션 목록을 __transaction_state 토픽에 append 한다.
     * 즉 "트랜잭션 상태"라는 것은 브로커 메모리의 마법이 아니라 복제되는 로그 레코드다.
     */
    @Test
    @Order(1)
    @DisplayName("Q1. __transaction_state 레코드 키 안에 transactional.id가 그대로 들어 있다")
    void transactionStateTopicContainsTransactionalId() throws Exception {
        System.out.println("\n  Q1: 트랜잭션 한 번 수행 → __transaction_state 원시 바이트 스캔");

        // 아주 단순한 트랜잭션 한 건. 목적은 출력 내용이 아니라 '코디네이터가 상태를 적게 만드는 것'이다.
        try (KafkaProducer<String, String> producer = transactionalProducer(txId)) {
            producer.initTransactions();     // → Empty 상태 레코드
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(OUT_TOPIC, "q1", "hello-internal"));
            producer.commitTransaction();    // → Ongoing / PrepareCommit / CompleteCommit 레코드
        }

        // 커밋 응답을 받았더라도 상태 레코드가 HW까지 복제되는 데 아주 짧은 시간이 걸릴 수 있다.
        Thread.sleep(1000);

        ScanResult result = scanInternalTopic(TX_STATE_TOPIC, txId, SCAN_TIMEOUT_MS);

        System.out.printf("  %s 전체 스캔: %d건 읽음, 키에 '%s'가 들어 있는 레코드 %d건%n",
                TX_STATE_TOPIC, result.totalRead(), txId, result.matches().size());
        printMatchTable(result.matches());
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 키(key)는 '버전 헤더 + transactional.id 문자열' 구조라 ASCII가 그대로 보인다.");
        System.out.println("  - 값(value)은 브로커 내부 스키마(producerId, epoch, 상태, 참여 파티션)로 직렬화되어 있다.");
        System.out.println("  - 같은 txId에 대해 여러 건이 보이면 상태 전이(Ongoing→PrepareCommit→CompleteCommit)의 흔적이다.");
        System.out.println("  - 이 토픽은 compact 정책이라 결국 txId별 최신 상태만 남는다.");
        printSeparator();

        assertThat(result.matches())
                .as("트랜잭션을 수행했다면 %s 에 그 transactional.id를 키로 갖는 레코드가 있어야 한다",
                        TX_STATE_TOPIC)
                .isNotEmpty();
    }

    /**
     * Q2. __consumer_offsets 에 내 group.id가 적혀 있는가?
     *
     * Lab 03의 consume-transform-produce와 같은 흐름이다. 다만 여기서 관심사는 결과가 아니라
     * "sendOffsetsToTransaction()이 대체 무엇을 하는가"이다.
     * 답: 트랜잭션 프로듀서가 __consumer_offsets 토픽에 오프셋 레코드를 쓴다.
     * 그저 토픽 쓰기이기 때문에 출력 토픽 쓰기와 같은 트랜잭션에 묶을 수 있는 것이다.
     */
    @Test
    @Order(2)
    @DisplayName("Q2. __consumer_offsets 레코드 키 안에 consumer group id가 그대로 들어 있다")
    void consumerOffsetsTopicContainsGroupId() throws Exception {
        System.out.println("\n  Q2: sendOffsetsToTransaction 으로 커밋 → __consumer_offsets 원시 바이트 스캔");

        // 입력 토픽에 씨앗 메시지를 넣는다(트랜잭션과 무관하므로 일반 프로듀서).
        try (KafkaProducer<String, String> seed = plainProducer()) {
            seed.send(new ProducerRecord<>(IN_TOPIC, "q2", "seed-1"));
            seed.send(new ProducerRecord<>(IN_TOPIC, "q2", "seed-2"));
            seed.flush();
        }

        // Q1과 같은 transactional.id를 재사용한다. 앞의 프로듀서는 이미 닫혔으므로
        // 새 프로듀서가 initTransactions()에서 epoch를 올리며 정상적으로 이어받는다.
        try (KafkaConsumer<String, String> consumer = consumer(groupId, "read_committed");
             KafkaProducer<String, String> producer = transactionalProducer(txId)) {

            producer.initTransactions();
            consumer.subscribe(List.of(IN_TOPIC));

            List<ConsumerRecord<String, String>> input = pollUntil(consumer, 2, 10_000);
            assertThat(input).as("스캔 대상이 될 오프셋을 만들려면 입력을 먼저 읽어야 한다").hasSize(2);

            producer.beginTransaction();
            for (ConsumerRecord<String, String> record : input) {
                producer.send(new ProducerRecord<>(OUT_TOPIC, record.key(), record.value().toUpperCase()));
            }
            // 이 한 줄이 __consumer_offsets 에 대한 '쓰기'다.
            producer.sendOffsetsToTransaction(nextOffsets(input), consumer.groupMetadata());
            producer.commitTransaction();
        }

        Thread.sleep(1000);

        ScanResult result = scanInternalTopic(CONSUMER_OFFSETS_TOPIC, groupId, SCAN_TIMEOUT_MS);

        System.out.printf("  %s 전체 스캔: %d건 읽음, 키에 '%s'가 들어 있는 레코드 %d건%n",
                CONSUMER_OFFSETS_TOPIC, result.totalRead(), groupId, result.matches().size());
        printMatchTable(result.matches());
        System.out.println();
        System.out.println("  결과 해석:");
        System.out.println("  - 오프셋 커밋은 API 호출이 아니라 __consumer_offsets 토픽에 대한 append다.");
        System.out.println("  - 키에는 group.id(+ 토픽·파티션)가, 값에는 커밋 오프셋과 메타데이터가 들어 있다.");
        System.out.println("  - '토픽 쓰기'이기 때문에 출력 토픽 send와 같은 트랜잭션 안에 넣을 수 있다 → Lab 03의 원자성.");
        System.out.println("  - 그룹 가입 자체(GroupMetadata)도 같은 토픽에 기록되므로 여러 건이 잡힐 수 있다.");
        printSeparator();

        assertThat(result.matches())
                .as("sendOffsetsToTransaction 으로 커밋했다면 %s 에 그 group.id를 키로 갖는 레코드가 있어야 한다",
                        CONSUMER_OFFSETS_TOPIC)
                .isNotEmpty();
    }

    /**
     * Q3. 사람이 읽을 수 있는 형태로 덤프한다 (출력 전용, 단정하지 않음).
     *
     * Q1/Q2는 "바이트 안에 문자열이 있다"까지만 보여 준다. 값(value)이 실제로 무엇인지 보려면
     * 브로커가 제공하는 MessageFormatter를 써야 한다. 문제는 이 포매터의 클래스 이름이
     * Kafka 버전에 따라 바뀐다는 점이다(3.x의 scala 패키지 → 4.x의 tools 패키지).
     * 그래서 후보를 순서대로 시도하고 성공한 것만 보여 준다. 실패해도 테스트는 통과시킨다 —
     * 포매터 가용성은 이 실습이 검증하려는 명제가 아니라 버전 세부사항이기 때문이다.
     */
    @Test
    @Order(3)
    @DisplayName("Q3. kafka-console-consumer의 --formatter로 __transaction_state를 사람이 읽는 형태로 덤프한다 (출력 전용)")
    void dumpTransactionStateWithFormatter() {
        System.out.println("\n  Q3: 컨테이너 안에서 kafka-console-consumer.sh --formatter 실행 (출력 전용)");

        String container = findContainerByService("kafka-1");
        assumeTrue(container != null,
                "docker CLI 또는 kafka-1 컨테이너를 찾을 수 없어 이 항목만 건너뜁니다. "
                        + "(Q1/Q2는 docker 없이도 동작합니다)");

        System.out.printf("  대상 컨테이너: %s%n", container);

        // 버전별 포매터 클래스 후보. 앞에서부터 시도한다.
        List<String> formatterCandidates = List.of(
                // Kafka 3.x (이 저장소의 docker-compose 기준: apache/kafka:3.7.0)
                "kafka.coordinator.transaction.TransactionLog$TransactionLogMessageFormatter",
                // Kafka 4.x — 포매터가 tools 모듈로 이동했다
                "org.apache.kafka.tools.consumer.TransactionLogMessageFormatter",
                // 일부 버전에서 쓰이는 coordinator 패키지 경로
                "org.apache.kafka.coordinator.transaction.TransactionLogMessageFormatter"
        );

        boolean printed = false;
        for (String formatter : formatterCandidates) {
            System.out.printf("%n  시도: --formatter %s%n", formatter);

            String output = runDockerExec(container,
                    "/opt/kafka/bin/kafka-console-consumer.sh",
                    "--bootstrap-server", "localhost:9092",
                    "--topic", TX_STATE_TOPIC,
                    "--from-beginning",
                    "--formatter", formatter,
                    // 내부 토픽을 명시적으로 지정해 읽는다
                    "--consumer-property", "exclude.internal.topics=false",
                    // 덤프만 할 뿐이므로 오프셋을 커밋하지 않는다 — 관측이 대상을 오염시키지 않게 한다
                    "--consumer-property", "enable.auto.commit=false",
                    "--max-messages", "20",
                    "--timeout-ms", "8000");

            if (looksLikeFormatterFailure(output)) {
                System.out.println("    → 이 브로커 버전에서는 사용할 수 없는 포매터입니다. 다음 후보를 시도합니다.");
                continue;
            }

            List<String> lines = meaningfulLines(output);
            if (lines.isEmpty()) {
                System.out.println("    → 포매터는 동작했지만 출력할 레코드가 없었습니다.");
                continue;
            }

            System.out.println("    → 성공. 덤프 결과:");
            lines.stream().limit(20).forEach(line -> System.out.println("      " + line));
            printed = true;
            break;
        }

        System.out.println();
        if (!printed) {
            System.out.println("  이 브로커 버전에서는 포매터를 찾지 못했습니다.");
            System.out.println("  (Q3은 관찰용 보조 항목이라 실패해도 명제 검증에는 영향이 없습니다.");
            System.out.println("   Q1/Q2에서 이미 '내부 토픽에 레코드가 쌓인다'는 사실은 확인했습니다.)");
        } else {
            System.out.println("  결과 해석:");
            System.out.println("  - 값에는 producerId / producerEpoch / state / partitions / txnTimeoutMs 등이 들어 있다.");
            System.out.println("  - Q1에서 바이트로만 봤던 것이 실제로는 이런 구조체였다는 뜻이다.");
            System.out.println("  - 포매터는 '읽는 방법'일 뿐, 저장 형태는 여전히 평범한 토픽 레코드다.");
        }
        printSeparator();
    }

    // ── 내부 토픽 스캔 ─────────────────────────────────────────────

    /** 스캔 결과: 전체 몇 건을 훑었고, 그중 키가 일치한 레코드는 무엇인지. */
    private record ScanResult(int totalRead, List<ConsumerRecord<byte[], byte[]>> matches) {}

    /**
     * 내부 토픽 전체를 원시 바이트로 훑으면서 키에 needle 문자열이 들어 있는 레코드를 모은다.
     *
     * 특정 id가 어느 파티션에 들어가는지는 이론상 계산할 수 있지만(해시 % 파티션수),
     * Kafka 내부 해시 구현에 의존하게 되므로 여기서는 전체 파티션을 훑는다.
     * subscribe 대신 assign을 쓰는 이유: 리밸런스를 기다리지 않아 빠르고,
     * 그룹에 가입하지 않으므로 이 관측 행위가 __consumer_offsets를 오염시키지 않는다.
     *
     * isolation.level은 기본값(read_uncommitted)이면 충분하다.
     * 내부 토픽의 상태 레코드 자체는 트랜잭션 레코드가 아니기 때문이다.
     */
    private static ScanResult scanInternalTopic(String topic, String needle, long timeoutMs) {
        List<ConsumerRecord<byte[], byte[]>> matches = new ArrayList<>();
        int totalRead = 0;

        try (KafkaConsumer<byte[], byte[]> consumer = byteArrayConsumer()) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            assertThat(infos).as("%s 토픽의 파티션 정보를 읽을 수 있어야 한다", topic).isNotNull();

            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();

            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    totalRead++;
                    if (record.key() != null && containsAscii(record.key(), needle)) {
                        matches.add(record);
                    }
                }
                // 모든 파티션의 끝에 도달했으면 타임아웃을 기다리지 않고 끝낸다.
                boolean reachedEnd = partitions.stream().allMatch(tp ->
                        consumer.position(tp) >= endOffsets.getOrDefault(tp, 0L));
                if (reachedEnd) {
                    break;
                }
            }
        }
        return new ScanResult(totalRead, matches);
    }

    /**
     * 내부 토픽 전용 Consumer.
     * TxHelper.consumer()는 StringDeserializer로 고정이라 내부 토픽의 바이너리 키/값을 읽으면
     * 깨진 문자열이 되거나 디코딩 과정에서 원본 바이트를 잃는다. 그래서 여기서만 별도로 만든다.
     * (TxHelper는 공용 유틸이므로 이 실습 하나를 위해 수정하지 않는다.)
     */
    private static KafkaConsumer<byte[], byte[]> byteArrayConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        // assign()만 쓰므로 그룹에 실제로 가입하지는 않지만, 설정상 값은 채워 둔다.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "tx-lab10-scanner-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        // 관측이 대상을 바꾸면 안 되므로 오프셋을 커밋하지 않는다.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }

    /**
     * 바이트 배열 안에 ASCII 문자열이 부분 문자열로 들어 있는지 본다.
     *
     * ISO_8859_1을 쓰는 이유: 이 바이트 배열은 텍스트가 아니라 '버전 헤더 + 길이 접두사 + 문자열'이
     * 섞인 바이너리 구조체다. UTF-8로 디코딩하면 유효하지 않은 바이트 시퀀스가 U+FFFD(치환 문자)로
     * 뭉개져서 원본 바이트 위치가 어긋난다. ISO_8859_1은 0~255 바이트를 U+0000~U+00FF 문자에
     * 1:1로 매핑하므로 손실 없이 '바이트를 문자처럼' 다룰 수 있다. ASCII 범위의 id를 찾는 데는
     * 이 단순한 방식으로 충분하며, 내부 레코드 스키마를 직접 파싱하지 않아 버전 변화에도 안전하다.
     */
    private static boolean containsAscii(byte[] bytes, String needle) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(needle);
    }

    /** 찾은 레코드를 표로 출력한다. 값이 null이면 톰스톤(compact 토픽의 삭제 표시)이다. */
    private static void printMatchTable(List<ConsumerRecord<byte[], byte[]>> matches) {
        if (matches.isEmpty()) {
            System.out.println("    (일치하는 레코드 없음)");
            return;
        }
        System.out.println();
        System.out.printf("    %-10s %-8s %-10s %s%n", "partition", "offset", "key(bytes)", "value(bytes)");
        System.out.println("    " + "-".repeat(52));
        for (ConsumerRecord<byte[], byte[]> record : matches) {
            String valueDesc = record.value() == null
                    ? "null (톰스톤)"
                    : String.valueOf(record.value().length);
            System.out.printf("    %-10d %-8d %-10d %s%n",
                    record.partition(),
                    record.offset(),
                    record.key().length,
                    valueDesc);
        }
    }

    // ── 실습 보조 ──────────────────────────────────────────────────

    /** 기대 개수를 채우거나 타임아웃될 때까지 poll한다. */
    private static List<ConsumerRecord<String, String>> pollUntil(
            KafkaConsumer<String, String> consumer, int expected, long timeoutMs) {

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (records.size() < expected && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(300)).forEach(records::add);
        }
        return records;
    }

    /** 트랜잭션에 태울 오프셋 맵. 커밋 값은 '다음에 읽어야 할 위치' = offset + 1 이다. */
    private static Map<TopicPartition, OffsetAndMetadata> nextOffsets(
            List<ConsumerRecord<String, String>> records) {

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            TopicPartition tp = new TopicPartition(record.topic(), record.partition());
            long next = record.offset() + 1;
            OffsetAndMetadata prev = offsets.get(tp);
            if (prev == null || prev.offset() < next) {
                offsets.put(tp, new OffsetAndMetadata(next));
            }
        }
        return offsets;
    }

    // ── docker 실행 ────────────────────────────────────────────────
    // Lab 07(kafka-study)·Lab 08과 같은 관례를 그대로 쓴다.
    // compose 라벨로 컨테이너 이름을 찾은 뒤 docker exec 한다.
    // docker compose 하위 명령을 쓰지 않는 이유: 테스트의 작업 디렉터리는 Gradle 모듈 디렉터리라
    // 프로젝트 루트의 docker-compose.yml을 찾지 못한다. 라벨 조회는 위치에 영향받지 않는다.

    /** docker ps 로 compose 서비스명에 해당하는 컨테이너 이름을 찾는다. 못 찾으면 null. */
    private static String findContainerByService(String service) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "ps",
                    "--filter", "label=com.docker.compose.service=" + service,
                    "--filter", "status=running",
                    "--format", "{{.Names}}"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(5, TimeUnit.SECONDS);
            return Arrays.stream(out.split("\\r?\\n"))
                    .filter(s -> !s.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** docker exec 으로 컨테이너 안에서 명령을 실행하고 stdout+stderr를 반환한다. */
    private static String runDockerExec(String container, String... cmd) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("exec");
            command.add(container);
            command.addAll(Arrays.asList(cmd));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return out + "\n[docker exec 가 30초 안에 끝나지 않아 중단함]";
            }
            return out;
        } catch (Exception e) {
            return "[docker exec 실패: " + e.getMessage() + "]";
        }
    }

    /** 포매터 클래스를 못 찾았거나 옵션이 지원되지 않는 경우를 판별한다. */
    private static boolean looksLikeFormatterFailure(String output) {
        return output.contains("ClassNotFoundException")
                || output.contains("NoClassDefFoundError")
                || output.contains("Could not find or load main class")
                || output.contains("Unrecognized option")
                || output.contains("class name not found")
                || output.contains("docker exec 실패")
                || output.contains("docker exec 가 30초");
    }

    /**
     * 덤프 출력에서 의미 있는 줄만 남긴다.
     * --timeout-ms 로 끝낸 console consumer는 정상 종료 시에도 TimeoutException과
     * "Processed a total of N messages" 를 찍으므로 걸러 낸다.
     */
    private static List<String> meaningfulLines(String output) {
        return Arrays.stream(output.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("Processed a total of"))
                .filter(line -> !line.contains("TimeoutException"))
                .filter(line -> !line.startsWith("[") || line.contains("transactionalId"))
                .toList();
    }
}
