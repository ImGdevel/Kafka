package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TransactionListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import org.junit.jupiter.api.*;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 09 — Kafka Streams의 exactly_once_v2
 *
 * 검증 명제: "Kafka Streams의 processing.guarantee=exactly_once_v2는 Lab03에서 손으로 짠
 *            consume-transform-produce 루프를 자동화한 것이다 — 새로운 마법이 아니다"
 *
 * Lab03에서는 beginTransaction() → send() → sendOffsetsToTransaction() → commitTransaction()을
 * 우리가 직접 순서대로 호출했다. Kafka Streams는 같은 루프를 프레임워크가 대신 돌린다.
 * 설정 한 줄(processing.guarantee=exactly_once_v2)로 바뀌는 것은 "누가 그 호출을 하느냐"뿐이고,
 * 브로커 관점에서 생기는 것은 완전히 동일하다.
 *   - 코디네이터에 등록되는 똑같은 transactional.id (Lab01에서 손으로 준 것, Lab05에서 Spring이 만든 것과 같은 레지스트리)
 *   - 출력 파티션에 쌓이는 똑같은 control batch(커밋 마커) — Lab02 Q3 / Lab07의 마커 산술이 그대로 적용된다
 *   - __consumer_offsets에 트랜잭션과 함께 확정되는 똑같은 입력 오프셋 (Lab03 Q1)
 * 즉 Streams가 주는 것은 "새로운 보장"이 아니라 "루프 자동화 + 태스크 단위 생명주기 관리"다.
 * 보장은 Lab01~08에서 본 그대로 브로커가 준다.
 *
 * Q1. 입력 토픽 → 대문자 변환 → 출력 토픽 이라는 최소 토폴로지를 exactly_once_v2로 돌린다.
 *     입력 N건을 미리 넣어두면, 출력 토픽에서 read_committed 소비자에게 정확히 N건이 보이는가?
 *     (Streams가 커밋을 마쳐야 read_committed에 보이므로, 고정 sleep이 아니라 폴링으로 기다린다.)
 * Q2. 출력 토픽의 끝 오프셋(HW)이 출력 메시지 수보다 크다 = control batch(커밋 마커)가 존재한다.
 *     Streams도 내부적으로 트랜잭션을 쓴다는 물증이다. Lab02 Q3에서 세운
 *     "끝 오프셋 − 메시지 수 = 커밋한 트랜잭션 수" 산술이 Streams에도 그대로 적용된다.
 *     다만 마커 개수는 commit.interval.ms와 커밋 타이밍에 좌우되므로 "1개 이상"만 단정하고 실제 값은 출력한다.
 * Q3. AdminClient.listTransactions()로 Streams가 스스로 만든 transactional.id를 조회한다.
 *     그 id 안에 application.id가 들어 있다(전체 형식은 버전 구현 세부사항이라 contains 수준으로만 단정).
 *     Lab01의 손으로 준 txId, Lab05의 Spring이 만든 txId, 여기 Streams가 만든 txId가
 *     모두 같은 코디네이터 레지스트리에 들어간다 — 추상화 계층이 달라져도 등록되는 곳은 하나다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab09*' --info
 */
@Tag("lab")
@DisplayName("Lab 09 — Kafka Streams exactly_once_v2")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab09StreamsExactlyOnceTest {

    private static final String INPUT_TOPIC  = "tx-lab09-in";
    private static final String OUTPUT_TOPIC = "tx-lab09-out";

    private static final List<String> ALL_TOPICS = List.of(INPUT_TOPIC, OUTPUT_TOPIC);

    /**
     * Q3에서 listTransactions()로 되찾아야 하므로 고정 문자열을 쓴다(nanoTime 금지).
     * Streams는 application.id를 컨슈머 그룹 이름으로도, transactional.id의 접두부로도, 내부 토픽 접두사로도 쓴다.
     * 고정 이름의 대가는 "이전 실행의 잔재"인데, 그건 state.dir을 매 실행 유니크하게 잡고
     * @AfterAll에서 토픽을 정리하는 것으로 막는다.
     */
    private static final String APPLICATION_ID = "tx-lab09-uppercase-app";

    /**
     * 로컬 상태 디렉터리. 이 토폴로지는 stateless(map만)라 실제로 쓸 일이 거의 없지만,
     * Streams는 무조건 이 디렉터리를 잡고 태스크 체크포인트를 관리한다.
     * 이전 실행의 체크포인트가 남아 있으면 결과가 달라 보일 수 있으므로 실행마다 새 경로를 쓴다.
     */
    private static final String STATE_DIR =
            Paths.get(System.getProperty("java.io.tmpdir"), "tx-lab09-state-" + System.nanoTime()).toString();

    private static final int MESSAGE_COUNT = 5;

    /** Q1이 띄우고 finally에서 닫는다. @AfterAll의 방어적 정리를 위해 필드로 둔다. */
    private static KafkaStreams streams;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 09: Kafka Streams exactly_once_v2",
                "Streams의 EOS는 Lab03의 consume-transform-produce 루프를 프레임워크가 대신 도는 것이다");
        for (String topic : ALL_TOPICS) {
            createTopic(topic, 1, (short) 3);
        }
    }

    @AfterAll
    static void tearDown() {
        // 1) 혹시 Q1이 비정상 종료해 Streams가 살아 있으면 여기서 확실히 닫는다.
        //    닫지 않으면 스트림 스레드가 계속 돌면서 뒤따르는 토픽 삭제와 경쟁한다.
        closeStreamsQuietly();

        // 2) 실습 토픽 정리
        for (String topic : ALL_TOPICS) {
            deleteTopic(topic);
        }

        // 3) Streams 내부 토픽 정리.
        //    이 토폴로지는 stateless(mapValues만)라 repartition/changelog 토픽이 생기지 않는 것이 정상이다.
        //    그래도 application.id로 시작하는 토픽이 남았다면 다음 실행에 영향을 주므로 지운다.
        //    (INPUT/OUTPUT 토픽 이름은 application.id로 시작하지 않으므로 여기 걸리지 않는다.)
        try (AdminClient admin = admin()) {
            Set<String> all = admin.listTopics().names().get();
            List<String> internal = all.stream()
                    .filter(name -> name.startsWith(APPLICATION_ID))
                    .toList();
            if (internal.isEmpty()) {
                System.out.println("  Streams 내부 토픽 없음 — stateless 토폴로지라 예상대로다.");
            } else {
                System.out.printf("  Streams 내부 토픽 정리: %s%n", internal);
                admin.deleteTopics(internal).all().get();
            }
        } catch (Exception ignored) {
        }

        // 4) 로컬 상태 디렉터리 정리 (실패해도 무해하므로 best-effort)
        deleteRecursivelyQuietly(new File(STATE_DIR));
    }

    @Test
    @Order(1)
    @DisplayName("Q1. exactly_once_v2 토폴로지의 출력이 read_committed 소비자에게 N건 그대로 보인다")
    void streamsWithExactlyOnceV2ProducesCommittedOutput() throws Exception {
        // 입력은 트랜잭션과 무관하게 미리 넣어둔다 — 검증 대상은 Streams가 "읽고 쓰는" 쪽이다.
        seedInput(MESSAGE_COUNT);

        Topology topology = buildTopology();
        System.out.println("  토폴로지:");
        System.out.println(indent(topology.describe().toString()));

        streams = new KafkaStreams(topology, streamsProps());

        List<ConsumerRecord<String, String>> output;
        try {
            streams.start();
            awaitRunning(Duration.ofSeconds(60));

            // Streams가 트랜잭션을 커밋해야 비로소 read_committed에 보인다.
            // commit.interval.ms=1000이므로 길어야 몇 초다. 고정 sleep 대신 폴링한다.
            output = awaitCommittedOutput(MESSAGE_COUNT, 40_000);
        } finally {
            // KafkaStreams는 반드시 닫는다. 닫지 않으면 스트림 스레드/프로듀서/컨슈머가 살아남아
            // 뒤이은 토픽 삭제와 다음 실습을 방해한다.
            closeStreamsQuietly();
        }

        printRecords("출력(read_committed)", output);
        System.out.printf("  Streams 컨슈머 그룹 = application.id = %s%n", APPLICATION_ID);
        System.out.println("  → 우리가 짠 코드는 mapValues 한 줄뿐이다.");
        System.out.println("     beginTransaction / sendOffsetsToTransaction / commitTransaction은 Streams가 대신 호출했다.");
        printSeparator();

        assertThat(output)
                .as("exactly_once_v2로 커밋된 출력은 read_committed 소비자에게 입력과 같은 %d건이 보인다", MESSAGE_COUNT)
                .hasSize(MESSAGE_COUNT);
        assertThat(output).extracting(ConsumerRecord::value)
                .as("토폴로지의 변환(대문자)이 그대로 반영되어야 한다")
                .containsExactlyInAnyOrder(expectedOutputValues());
    }

    @Test
    @Order(2)
    @DisplayName("Q2. 출력 토픽에 control batch(커밋 마커)가 남아 있다 — Streams도 트랜잭션을 쓴다")
    void streamsLeavesControlBatchesInOutputTopic() {
        long endOffset = highWatermark(OUTPUT_TOPIC, 0);
        long markers = endOffset - MESSAGE_COUNT;   // Lab02 Q3 / Lab07의 산술 그대로

        System.out.printf("  출력 토픽 끝 오프셋(HW)=%d, 실제 메시지=%d → 마커=%d개%n",
                endOffset, MESSAGE_COUNT, markers);
        System.out.println("  → 마커가 존재한다 = Streams가 내부적으로 트랜잭션을 열고 닫았다는 물증이다.");
        System.out.println("     마커 개수는 commit.interval.ms와 커밋 타이밍에 좌우되므로 정확한 값은 단정하지 않는다.");
        System.out.println("     (입력을 미리 다 넣어두면 보통 커밋 1~2회로 끝나 마커도 1~2개다.)");
        printSeparator();

        assertThat(endOffset)
                .as("트랜잭션을 썼다면 끝 오프셋이 메시지 수(%d)보다 반드시 크다 — 그 차이가 control batch다", MESSAGE_COUNT)
                .isGreaterThan(MESSAGE_COUNT);
        assertThat(markers)
                .as("커밋 마커가 최소 1개는 있어야 한다 (개수는 커밋 타이밍에 따라 달라지므로 하한만 단정)")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(3)
    @DisplayName("Q3. Streams가 만든 transactional.id도 같은 코디네이터 레지스트리에 등록된다")
    void streamsRegistersItsOwnTransactionalId() throws Exception {
        // listTransactions()는 진행 중인 것뿐 아니라 최근 종료된 트랜잭션도 돌려준다.
        // (코디네이터가 transactional.id.expiration.ms 동안 상태를 들고 있기 때문 — Lab05 Q2와 같은 사정이다.)
        // 그래서 Q1에서 Streams를 이미 닫았어도 방금 쓴 id가 보인다.
        List<TransactionListing> mine = findTransactionsContaining(APPLICATION_ID, 15_000);

        System.out.println("  Streams가 코디네이터에 등록한 transactional.id:");
        for (TransactionListing listing : mine) {
            System.out.printf("      %s  (state=%s, producerId=%d)%n",
                    listing.transactionalId(), listing.state(), listing.producerId());
        }
        System.out.println("  → 우리는 transactional.id를 한 번도 지정하지 않았다. application.id만 줬을 뿐이다.");
        System.out.println("     Streams가 application.id를 바탕으로 스스로 id를 만들어 코디네이터에 등록한다.");
        System.out.println("     Lab01: 우리가 손으로 준 txId");
        System.out.println("     Lab05: Spring이 prefix에 접미사를 붙여 만든 txId");
        System.out.println("     Lab09: Streams가 application.id로 만든 txId");
        System.out.println("     → 만든 주체만 다를 뿐 셋 다 같은 __transaction_state 레지스트리에 들어간다.");
        printSeparator();

        assertThat(mine)
                .as("Streams가 만든 트랜잭션이 실제로 브로커 코디네이터에 등록되어 있어야 한다")
                .isNotEmpty();
        assertThat(mine)
                .as("전체 id 형식(접미사)은 Streams 버전의 구현 세부사항이므로 application.id 포함 여부만 단정한다")
                .allSatisfy(listing ->
                        assertThat(listing.transactionalId()).contains(APPLICATION_ID));

        // Lab01/Lab05에서 쓴 것과 완전히 같은 조회 경로로 개별 조회도 되는지 확인한다.
        String actualId = mine.get(0).transactionalId();
        assertThat(describeTransaction(actualId))
                .as("조회 방법이 Lab01(손으로 준 txId)과 다르지 않다 — 코디네이터 입장에서 특별한 id가 아니다")
                .isNotNull();
    }

    // ── 토폴로지 / 설정 ────────────────────────────────────────────

    /**
     * 최소 토폴로지: 입력 토픽 → 대문자 변환 → 출력 토픽.
     * Lab03의 transform() 단계와 의도적으로 같은 변환을 쓴다 — 달라진 것은 루프를 누가 도느냐뿐이다.
     * mapValues만 쓰므로 stateless다. 즉 repartition/changelog 같은 내부 토픽이 생기지 않는다.
     */
    private static Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();
        KStream<String, String> source = builder.stream(INPUT_TOPIC);
        source.mapValues(value -> value.toUpperCase()).to(OUTPUT_TOPIC);
        return builder.build();
    }

    private static Properties streamsProps() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        // 이 한 줄이 Lab03에서 손으로 짠 루프 전체를 대체한다.
        // exactly_once_v2 = KIP-447 방식. 입력 파티션마다 Producer를 만들던 v1(EXACTLY_ONCE)과 달리
        // 스레드당 Producer 하나가 모든 파티션의 오프셋을 한 트랜잭션에 태운다(Lab05 Q2의 EOSMode.V2와 같은 이야기).
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);

        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

        // 커밋 주기를 짧게 잡아 테스트가 오래 기다리지 않게 한다.
        // 실무 EOS 기본값은 100ms(EOS일 때)지만, 여기서는 "커밋 단위가 눈에 보이는" 크기로 명시한다.
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);

        // exactly_once_v2는 브로커의 transaction.state.log.replication.factor(=3)를 전제로 한다.
        // Streams는 내부 토픽 RF를 기본 1로 만들려 하므로 3으로 맞춰준다.
        // (이 토폴로지는 내부 토픽을 만들지 않지만, 설정을 빠뜨리면 stateful 토폴로지로 확장할 때 바로 깨진다.)
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);

        // 실행마다 새 상태 디렉터리 — 이전 실행의 체크포인트가 결과를 흐리지 않게 한다.
        props.put(StreamsConfig.STATE_DIR_CONFIG, STATE_DIR);

        // Streams의 기본값도 earliest지만, 실습에서는 "처음부터 읽는다"를 명시해 둔다.
        props.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");

        return props;
    }

    // ── 보조 메서드 ────────────────────────────────────────────────

    /** 입력 토픽에 실습용 메시지를 미리 넣어둔다. 트랜잭션과 무관하므로 plainProducer를 쓴다. */
    private static void seedInput(int count) {
        try (KafkaProducer<String, String> producer = plainProducer()) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(INPUT_TOPIC, "key-" + i, "event-" + i));
            }
            producer.flush();
        }
    }

    private static String[] expectedOutputValues() {
        String[] values = new String[MESSAGE_COUNT];
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            values[i] = ("event-" + i).toUpperCase();
        }
        return values;
    }

    /** RUNNING이 될 때까지 폴링한다. 고정 sleep을 쓰지 않는다. */
    private static void awaitRunning(Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        System.out.print("  Streams 기동 대기");
        while (System.currentTimeMillis() < deadline) {
            KafkaStreams.State state = streams.state();
            if (state == KafkaStreams.State.RUNNING) {
                System.out.printf(" → %s%n", state);
                return;
            }
            if (state == KafkaStreams.State.ERROR || state == KafkaStreams.State.NOT_RUNNING) {
                System.out.printf(" → %s%n", state);
                throw new IllegalStateException("Streams가 기동에 실패했습니다. state=" + state);
            }
            System.out.print(".");
            Thread.sleep(300);
        }
        System.out.println();
        throw new IllegalStateException("Streams가 RUNNING이 되지 않았습니다. state=" + streams.state());
    }

    /**
     * 출력 토픽을 read_committed로 폴링하며 기대 건수가 찰 때까지 기다린다.
     * Streams가 트랜잭션을 커밋하기 전에는 아무것도 보이지 않는 것이 정상이므로
     * "안 보인다 → 실패"가 아니라 "커밋될 때까지 기다린다"로 처리한다.
     */
    private static List<ConsumerRecord<String, String>> awaitCommittedOutput(int expected, long timeoutMs) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        System.out.print("  Streams 커밋 대기");
        try (KafkaConsumer<String, String> consumer =
                     consumer("lab09-verify-" + System.nanoTime(), "read_committed")) {
            consumer.subscribe(List.of(OUTPUT_TOPIC));
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                System.out.print(".");
            }
        }
        System.out.printf(" → %d건%n", records.size());
        return records;
    }

    /**
     * transactionalId에 특정 문자열이 들어간 트랜잭션이 코디네이터에 나타날 때까지 잠깐 기다렸다가 반환한다.
     * 커밋 직후 곧바로 조회하면 아직 전파 전일 수 있어 짧게 재시도한다(Lab05 Q2와 같은 이유).
     */
    private static List<TransactionListing> findTransactionsContaining(String fragment, long timeoutMs)
            throws Exception {

        List<TransactionListing> found = List.of();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            found = listTransactions().stream()
                    .filter(listing -> listing.transactionalId().contains(fragment))
                    .toList();
            if (!found.isEmpty()) {
                break;
            }
            Thread.sleep(500);
        }
        return found;
    }

    /** Streams를 닫는다. 이미 닫혔거나 시작 전이면 아무 일도 하지 않는다. */
    private static void closeStreamsQuietly() {
        if (streams == null) {
            return;
        }
        try {
            streams.close(Duration.ofSeconds(30));
        } catch (Exception ignored) {
        } finally {
            streams = null;
        }
    }

    /** 로컬 상태 디렉터리를 지운다. 실패해도 실습 결과와 무관하므로 조용히 넘어간다. */
    private static void deleteRecursivelyQuietly(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursivelyQuietly(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** 토폴로지 설명을 실습 출력 들여쓰기에 맞춰 정렬한다. */
    private static String indent(String text) {
        return text.lines().map(line -> "      " + line).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }
}
