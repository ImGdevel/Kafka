package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.study.kafka.transaction.TxHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lab 03 — consume-transform-produce (EOS의 핵심 패턴)
 *
 * 검증 명제: "출력 produce와 입력 offset commit이 하나의 원자 단위가 된다"
 *
 * 스트림 처리 애플리케이션은 대부분 "입력 토픽에서 읽고 → 변환하고 → 출력 토픽에 쓰고 →
 * 입력 오프셋을 커밋"하는 모양이다. 여기서 출력 쓰기와 오프셋 커밋이 따로 놀면
 * 중간에 죽었을 때 중복(출력은 나갔는데 오프셋 미커밋) 또는 유실(오프셋은 커밋됐는데 출력이 안 나감)이 생긴다.
 * Kafka 트랜잭션은 이 둘을 같은 트랜잭션에 묶어 원자적으로 만든다.
 * (오프셋 커밋도 결국 __consumer_offsets 토픽에 대한 쓰기이기 때문에 가능한 일이다.)
 *
 * Q1. 읽고 → 변환하고 → 출력에 쓰고 → sendOffsetsToTransaction()으로 입력 오프셋을 같은 트랜잭션에 묶어 커밋한다.
 *     커밋 후 출력이 read_committed에 보이고, 입력 그룹의 committed offset도 함께 전진했는가?
 * Q2. 같은 흐름에서 커밋 직전에 abortTransaction()한다.
 *     출력도 안 보이고 입력 오프셋도 전진하지 않는가? (= 둘이 함께 롤백 → 재처리 가능)
 *     되감아 다시 처리하면 출력은 정확히 1건만 남는가?
 * Q3. 대조군 — 트랜잭션 없이 plainProducer + enable.auto.commit=true로 같은 일을 하면 원자성이 없다.
 *     오프셋 커밋과 send가 별개의 두 연산이므로 그 사이에서 죽으면 유실이 발생한다.
 *
 * 실행 방법:
 *   docker compose up -d
 *   ./gradlew :kafka-transaction:test --tests '*Lab03*' --info
 */
@Tag("lab")
@DisplayName("Lab 03 — consume-transform-produce")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Lab03ConsumeTransformProduceTest {

    // 테스트끼리 오프셋 상태가 섞이지 않도록 입력/출력 토픽과 컨슈머 그룹을 Q별로 완전히 분리한다.
    private static final String Q1_IN  = "tx-lab03-q1-in";
    private static final String Q1_OUT = "tx-lab03-q1-out";
    private static final String Q2_IN  = "tx-lab03-q2-in";
    private static final String Q2_OUT = "tx-lab03-q2-out";
    private static final String Q3_IN  = "tx-lab03-q3-in";
    private static final String Q3_OUT = "tx-lab03-q3-out";

    private static final List<String> ALL_TOPICS = List.of(Q1_IN, Q1_OUT, Q2_IN, Q2_OUT, Q3_IN, Q3_OUT);

    private static final String Q1_GROUP = "tx-lab03-q1-group";
    private static final String Q2_GROUP = "tx-lab03-q2-group";
    private static final String Q3_GROUP = "tx-lab03-q3-group";

    private static final String Q1_TX_ID = "tx-lab03-q1-processor";
    private static final String Q2_TX_ID = "tx-lab03-q2-processor";

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(isKafkaAvailable(), "Docker Kafka가 실행되지 않아 실습을 건너뜁니다.");
        printHeader("Lab 03: consume-transform-produce",
                "출력 produce와 입력 offset commit이 하나의 원자 단위가 되는가?");
        for (String topic : ALL_TOPICS) {
            createTopic(topic, 1, (short) 3);
        }
    }

    @AfterAll
    static void tearDown() {
        for (String topic : ALL_TOPICS) {
            deleteTopic(topic);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Q1. 커밋하면 출력 메시지와 입력 오프셋이 함께 전진한다")
    void commitAdvancesBothOutputAndInputOffset() throws Exception {
        seedInput(Q1_IN, "alpha", "beta", "gamma");

        // 입력 컨슈머는 반드시 enable.auto.commit=false 여야 한다.
        // 오프셋 커밋 주체가 컨슈머가 아니라 "트랜잭션 프로듀서"이기 때문이다.
        // TxHelper.consumer()가 이미 auto commit을 꺼두었고 isolation.level=read_committed로 만든다.
        try (KafkaConsumer<String, String> consumer = consumer(Q1_GROUP, "read_committed");
             KafkaProducer<String, String> producer = transactionalProducer(Q1_TX_ID)) {

            producer.initTransactions();
            consumer.subscribe(List.of(Q1_IN));

            List<ConsumerRecord<String, String>> input = pollUntil(consumer, 3, 10_000);
            printRecords("입력(read)", input);
            assertThat(input).as("변환할 입력 3건을 먼저 읽어야 한다").hasSize(3);

            producer.beginTransaction();

            // 1) 변환 결과를 출력 토픽으로 send
            for (ConsumerRecord<String, String> record : input) {
                producer.send(new ProducerRecord<>(Q1_OUT, record.key(), transform(record.value())));
            }

            // 2) 입력 오프셋을 같은 트랜잭션에 태운다.
            //    ConsumerGroupMetadata를 받는 오버로드를 쓴다(KIP-447).
            //    groupId 문자열만 받는 예전 오버로드는 deprecated이며, 컨슈머의 generation/memberId를
            //    함께 넘기지 않기 때문에 리밸런스로 이미 그룹에서 쫓겨난 좀비 컨슈머가 뒤늦게 커밋하는 것을
            //    코디네이터가 막지 못한다(펜싱 불가). groupMetadata()를 넘기면 세대가 낡은 요청이 거부된다.
            Map<TopicPartition, OffsetAndMetadata> offsets = nextOffsets(input);
            producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());

            // 3) send와 offset commit이 하나의 커밋으로 확정된다
            producer.commitTransaction();

            System.out.printf("  트랜잭션에 태운 오프셋: %s%n", offsets);
        }

        List<ConsumerRecord<String, String>> output =
                readCommitted(Q1_OUT, "lab03-q1-verify-" + System.nanoTime(), 3, 5000);
        long committed = committedOffset(Q1_GROUP, new TopicPartition(Q1_IN, 0));

        printRecords("출력(read_committed)", output);
        System.out.printf("  입력 그룹 committed offset: %d%n", committed);
        printSeparator();

        assertThat(output)
                .as("커밋된 트랜잭션의 출력은 read_committed 소비자에게 보인다")
                .hasSize(3);
        assertThat(output).extracting(ConsumerRecord::value)
                .containsExactlyInAnyOrder("ALPHA", "BETA", "GAMMA");
        assertThat(committed)
                .as("커밋할 오프셋은 '마지막으로 읽은 offset + 1' = 다음에 읽을 위치이므로 3이다")
                .isEqualTo(3);
    }

    @Test
    @Order(2)
    @DisplayName("Q2. 중단하면 출력과 입력 오프셋이 함께 롤백되고, 재처리해도 출력은 1건뿐이다")
    void abortRollsBackOutputAndInputOffsetTogether() throws Exception {
        seedInput(Q2_IN, "hello");

        TopicPartition inputPartition = new TopicPartition(Q2_IN, 0);

        try (KafkaConsumer<String, String> consumer = consumer(Q2_GROUP, "read_committed");
             KafkaProducer<String, String> producer = transactionalProducer(Q2_TX_ID)) {

            producer.initTransactions();
            consumer.subscribe(List.of(Q2_IN));

            List<ConsumerRecord<String, String>> input = pollUntil(consumer, 1, 10_000);
            printRecords("입력(read)", input);
            assertThat(input).hasSize(1);

            // ── 1차 시도: 출력도 보내고 오프셋도 태웠지만 커밋 직전에 실패했다고 가정한다 ──
            producer.beginTransaction();
            producer.send(new ProducerRecord<>(Q2_OUT, input.get(0).key(), transform(input.get(0).value())));
            producer.sendOffsetsToTransaction(nextOffsets(input), consumer.groupMetadata());
            producer.flush(); // 브로커 로그에는 이미 기록된 상태로 만든다 — 지워지는 게 아니라 걸러지는 것임을 보이기 위해
            producer.abortTransaction();

            // 안 보여야 하는 것을 검증하므로 기대 개수를 크게 잡아 타임아웃까지 기다린다
            List<ConsumerRecord<String, String>> afterAbort =
                    readCommitted(Q2_OUT, "lab03-q2-abort-" + System.nanoTime(), 99, 4000);
            long offsetAfterAbort = committedOffset(Q2_GROUP, inputPartition);

            printRecords("중단 후 출력(read_committed)", afterAbort);
            System.out.printf("  중단 후 입력 committed offset: %d  (-1 = 커밋된 오프셋 없음)%n", offsetAfterAbort);

            assertThat(afterAbort)
                    .as("중단된 트랜잭션의 출력은 read_committed에 보이지 않는다")
                    .isEmpty();
            assertThat(offsetAfterAbort)
                    .as("오프셋 커밋도 같은 트랜잭션 안에 있었으므로 함께 롤백된다 — 1로 전진하지 않는다")
                    .isLessThan(1L);

            // ── 2차 시도: 마지막 커밋 지점으로 되감아 재처리한다 ──
            // 중단해도 컨슈머의 메모리상 position은 이미 앞으로 나가 있으므로,
            // 실무 EOS 루프에서는 abort 직후 committed offset으로 seek해서 되감는다.
            rewindToCommitted(consumer);

            List<ConsumerRecord<String, String>> retry = pollUntil(consumer, 1, 10_000);
            printRecords("재처리 입력(read)", retry);
            assertThat(retry)
                    .as("오프셋이 전진하지 않았으므로 같은 레코드를 다시 읽을 수 있다")
                    .hasSize(1);
            assertThat(retry.get(0).offset()).isEqualTo(0);

            producer.beginTransaction();
            producer.send(new ProducerRecord<>(Q2_OUT, retry.get(0).key(), transform(retry.get(0).value())));
            producer.sendOffsetsToTransaction(nextOffsets(retry), consumer.groupMetadata());
            producer.commitTransaction();
        }

        List<ConsumerRecord<String, String>> finalOutput =
                readCommitted(Q2_OUT, "lab03-q2-final-" + System.nanoTime(), 99, 4000);
        long finalOffset = committedOffset(Q2_GROUP, inputPartition);

        printRecords("재처리 후 출력(read_committed)", finalOutput);
        System.out.printf("  재처리 후 입력 committed offset: %d%n", finalOffset);
        printSeparator();

        assertThat(finalOutput)
                .as("중단된 1차 출력은 걸러지고 커밋된 2차 출력만 보인다 — 두 번 처리했지만 결과는 정확히 1건")
                .hasSize(1);
        assertThat(finalOutput.get(0).value()).isEqualTo("HELLO");
        assertThat(finalOffset)
                .as("2차 트랜잭션이 커밋되면서 출력과 오프셋이 함께 확정된다")
                .isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("Q3. 대조군 — 트랜잭션 없이 auto commit을 쓰면 오프셋 커밋과 send가 별개라 유실이 난다")
    void withoutTransactionOffsetCommitAndSendAreSeparate() throws Exception {
        seedInput(Q3_IN, "will-be-lost");

        TopicPartition inputPartition = new TopicPartition(Q3_IN, 0);

        // ── 1차 시도: 읽고 → auto commit이 먼저 발화 → send 하기 전에 죽었다고 가정한다 ──
        // enable.auto.commit=true인 컨슈머는 poll() 안에서 주기적으로 알아서 오프셋을 커밋한다.
        // 즉 "언제 커밋되는지"가 애플리케이션 로직과 무관하게 결정된다 — 이것이 원자성이 없다는 말의 실체다.
        try (KafkaConsumer<String, String> consumer = autoCommitConsumer(Q3_GROUP)) {
            consumer.subscribe(List.of(Q3_IN));

            List<ConsumerRecord<String, String>> input = pollUntil(consumer, 1, 10_000);
            printRecords("입력(read)", input);
            assertThat(input).hasSize(1);

            // auto.commit.interval.ms=100 이므로 몇 번 더 poll하면 그 사이 오프셋이 커밋된다.
            drain(consumer, 1500);

            // 여기서 프로세스가 죽었다고 가정한다 — plainProducer.send()는 아직 한 번도 호출하지 않았다.
            System.out.println("  (가정) 오프셋은 커밋됐지만 출력 send 직전에 프로세스가 죽었다");
        }

        long committedAfterCrash = committedOffset(Q3_GROUP, inputPartition);
        List<ConsumerRecord<String, String>> outputAfterCrash =
                readCommitted(Q3_OUT, "lab03-q3-crash-" + System.nanoTime(), 99, 4000);

        System.out.printf("  크래시 후 입력 committed offset: %d%n", committedAfterCrash);
        printRecords("크래시 후 출력", outputAfterCrash);

        assertThat(committedAfterCrash)
                .as("트랜잭션과 무관하게 컨슈머가 스스로 커밋해 버렸다")
                .isEqualTo(1);
        assertThat(outputAfterCrash)
                .as("출력은 한 건도 나가지 않았다")
                .isEmpty();

        // ── 2차 시도: 재시작한 것처럼 같은 그룹으로 다시 읽어본다 ──
        try (KafkaConsumer<String, String> restarted = autoCommitConsumer(Q3_GROUP)) {
            restarted.subscribe(List.of(Q3_IN));
            List<ConsumerRecord<String, String>> retry = pollUntil(restarted, 1, 6000);

            printRecords("재시작 후 재처리 대상", retry);
            printSeparator();

            assertThat(retry)
                    .as("오프셋이 이미 전진해 있으므로 그 레코드를 다시 받을 기회가 없다 = 유실")
                    .isEmpty();
        }

        // 대조: Q1/Q2에서는 send와 offset commit이 한 트랜잭션이었기 때문에
        //       '오프셋만 커밋되고 출력은 없는' 이 중간 상태 자체가 만들어질 수 없다.
        List<ConsumerRecord<String, String>> outputFinal =
                readCommitted(Q3_OUT, "lab03-q3-final-" + System.nanoTime(), 99, 4000);
        assertThat(outputFinal)
                .as("입력 1건은 끝내 출력으로 이어지지 못했다")
                .isEmpty();
    }

    // ── 보조 메서드 ────────────────────────────────────────────────

    /** 변환 단계(transform). 여기서는 대문자로 바꾸는 것으로 대신한다. */
    private static String transform(String value) {
        return value.toUpperCase();
    }

    /** 입력 토픽에 실습용 메시지를 미리 넣어둔다. 트랜잭션과 무관하므로 plainProducer를 쓴다. */
    private static void seedInput(String topic, String... values) {
        try (KafkaProducer<String, String> producer = plainProducer()) {
            for (int i = 0; i < values.length; i++) {
                producer.send(new ProducerRecord<>(topic, "key-" + i, values[i]));
            }
            producer.flush();
        }
    }

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

    /** 결과를 쓰지 않고 일정 시간 계속 poll한다. auto commit이 발화하도록 만들 때 쓴다. */
    private static void drain(KafkaConsumer<String, String> consumer, long durationMs) {
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(200));
        }
    }

    /**
     * 트랜잭션에 태울 오프셋 맵을 만든다.
     * 커밋할 값은 읽은 레코드의 offset이 아니라 offset + 1 — "다음에 읽어야 할 위치"다.
     */
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

    /** 중단 후 마지막으로 커밋된 지점으로 되감는다. 커밋 기록이 없으면 처음부터 다시 읽는다. */
    private static void rewindToCommitted(KafkaConsumer<String, String> consumer) {
        Set<TopicPartition> assigned = consumer.assignment();
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(assigned);
        for (TopicPartition tp : assigned) {
            OffsetAndMetadata offset = committed.get(tp);
            if (offset == null) {
                consumer.seekToBeginning(List.of(tp));
            } else {
                consumer.seek(tp, offset.offset());
            }
        }
    }

    /** 컨슈머 그룹에 커밋된 오프셋을 조회한다. 커밋 기록이 없으면 -1을 반환한다. */
    private static long committedOffset(String groupId, TopicPartition partition) throws Exception {
        try (AdminClient admin = admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
            OffsetAndMetadata offset = offsets.get(partition);
            return offset == null ? -1L : offset.offset();
        }
    }

    /**
     * 대조군용 컨슈머 — enable.auto.commit=true.
     * TxHelper.consumer()는 auto commit을 꺼버리므로 여기서만 별도로 만든다.
     * auto.commit.interval.ms를 짧게 잡아 "언제 커밋될지 애플리케이션이 통제하지 못한다"는 점을 재현한다.
     */
    private static KafkaConsumer<String, String> autoCommitConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "100");
        return new KafkaConsumer<>(props);
    }
}
