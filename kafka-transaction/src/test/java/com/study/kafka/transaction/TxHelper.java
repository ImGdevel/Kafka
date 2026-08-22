package com.study.kafka.transaction;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TransactionDescription;
import org.apache.kafka.clients.admin.TransactionListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Kafka 트랜잭션 실습용 공통 유틸리티.
 * Docker Compose 3-broker KRaft 클러스터(9092/9094/9095)를 대상으로 한다.
 *
 * 트랜잭션 실습에서 반복적으로 필요한 것들만 모았다.
 * - transactional.id를 가진 Producer 생성
 * - isolation.level(read_committed / read_uncommitted)을 바꿔가며 소비
 * - __transaction_state 내부 토픽과 진행 중 트랜잭션 조회
 * - LSO(Last Stable Offset) 확인 — 커밋 마커로 생긴 오프셋 간격을 눈으로 보기 위함
 */
class TxHelper {

    static final String BOOTSTRAP_SERVERS = "localhost:9092,localhost:9094,localhost:9095";
    static final String TX_STATE_TOPIC = "__transaction_state";

    // ── 가용성 체크 ────────────────────────────────────────────────

    /** Docker Kafka가 실행 중인지 확인한다. */
    static boolean isKafkaAvailable() {
        try (Socket ignored = new Socket("localhost", 9092)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 클러스터의 브로커 수를 반환한다. 트랜잭션은 RF=3 / min ISR=2 전제이므로 3이어야 정상. */
    static int brokerCount() throws Exception {
        try (AdminClient admin = admin()) {
            Collection<Node> nodes = admin.describeCluster().nodes().get();
            return nodes.size();
        }
    }

    // ── AdminClient ────────────────────────────────────────────────

    static AdminClient admin() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        return AdminClient.create(props);
    }

    /** 토픽을 생성한다. 이미 존재하면 삭제 후 재생성한다. */
    static void createTopic(String name, int partitions, short replication) throws Exception {
        createTopicWithConfig(name, partitions, replication, Map.of());
    }

    /** 사용자 설정을 포함한 토픽을 생성한다. 이미 존재하면 삭제 후 재생성한다. */
    static void createTopicWithConfig(String name, int partitions, short replication,
                                      Map<String, String> configs) throws Exception {
        try (AdminClient admin = admin()) {
            try {
                admin.deleteTopics(List.of(name)).all().get();
                Thread.sleep(1500); // 삭제 완료 대기
            } catch (Exception ignored) {}

            NewTopic topic = new NewTopic(name, partitions, replication);
            if (!configs.isEmpty()) {
                topic.configs(configs);
            }
            admin.createTopics(List.of(topic)).all().get();
            Thread.sleep(500); // 생성 완료 대기
        }
    }

    /** 토픽을 삭제한다. 없으면 무시한다. */
    static void deleteTopic(String name) {
        try (AdminClient admin = admin()) {
            admin.deleteTopics(List.of(name)).all().get();
        } catch (Exception ignored) {}
    }

    static TopicDescription describeTopic(String name) throws Exception {
        try (AdminClient admin = admin()) {
            return admin.describeTopics(List.of(name)).allTopicNames().get().get(name);
        }
    }

    /** 토픽 설정 값 하나를 읽는다. 없으면 null. */
    static String topicConfig(String topic, String key) throws Exception {
        try (AdminClient admin = admin()) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            ConfigEntry entry = admin.describeConfigs(List.of(resource))
                    .all().get().get(resource).get(key);
            return entry == null ? null : entry.value();
        }
    }

    // ── 트랜잭션 상태 조회 ─────────────────────────────────────────

    /** 브로커가 알고 있는 모든 트랜잭션 목록(진행 중 + 최근 종료)을 반환한다. */
    static Collection<TransactionListing> listTransactions() throws Exception {
        try (AdminClient admin = admin()) {
            return admin.listTransactions().all().get();
        }
    }

    /** transactionalId의 현재 트랜잭션 상태를 반환한다. 없으면 null. */
    static TransactionDescription describeTransaction(String transactionalId) throws Exception {
        try (AdminClient admin = admin()) {
            return admin.describeTransactions(List.of(transactionalId))
                    .all().get().get(transactionalId);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Producer ───────────────────────────────────────────────────

    /**
     * 트랜잭션 Producer를 만든다. initTransactions()는 호출하지 않으므로 호출부에서 직접 한다.
     * transactional.id를 지정하면 enable.idempotence=true, acks=all이 강제된다(명시적으로도 설정).
     */
    static KafkaProducer<String, String> transactionalProducer(String transactionalId) {
        Properties props = baseProducerProps();
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    /** 추가 설정을 덮어쓸 수 있는 트랜잭션 Producer. (transaction.timeout.ms 실습 등에 사용) */
    static KafkaProducer<String, String> transactionalProducer(String transactionalId,
                                                              Map<String, String> overrides) {
        Properties props = baseProducerProps();
        props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.putAll(overrides);
        return new KafkaProducer<>(props);
    }

    /** 트랜잭션을 쓰지 않는 일반 Producer. 트랜잭션 유무 비교 실습에 사용. */
    static KafkaProducer<String, String> plainProducer() {
        return new KafkaProducer<>(baseProducerProps());
    }

    private static Properties baseProducerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }

    // ── Consumer ───────────────────────────────────────────────────

    /**
     * isolation.level을 지정한 Consumer를 만든다.
     * read_committed: 커밋된 트랜잭션 메시지만 보이고 LSO까지만 읽는다.
     * read_uncommitted: 진행 중/중단된 트랜잭션 메시지도 보인다(기본값).
     */
    static KafkaConsumer<String, String> consumer(String groupId, String isolationLevel) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, isolationLevel);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }

    /**
     * 토픽 전체를 timeoutMs 동안 읽는다. 기대 개수를 채우면 조기 반환한다.
     * "안 보여야 하는 메시지"를 검증할 때는 expectedCount를 크게 잡아 타임아웃까지 기다리게 한다.
     */
    static List<ConsumerRecord<String, String>> consumeAll(
            String topic, String groupId, String isolationLevel, int expectedCount, long timeoutMs) {

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        try (KafkaConsumer<String, String> consumer = consumer(groupId, isolationLevel)) {
            consumer.subscribe(List.of(topic));
            while (records.size() < expectedCount && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    static List<ConsumerRecord<String, String>> readCommitted(
            String topic, String groupId, int expectedCount, long timeoutMs) {
        return consumeAll(topic, groupId, "read_committed", expectedCount, timeoutMs);
    }

    static List<ConsumerRecord<String, String>> readUncommitted(
            String topic, String groupId, int expectedCount, long timeoutMs) {
        return consumeAll(topic, groupId, "read_uncommitted", expectedCount, timeoutMs);
    }

    // ── 오프셋 관측 ────────────────────────────────────────────────

    /**
     * read_committed 관점의 끝 오프셋(LSO, Last Stable Offset)을 반환한다.
     * 진행 중인 트랜잭션이 있으면 그 시작 지점에서 멈춘다.
     */
    static long lastStableOffset(String topic, int partition) {
        return endOffset(topic, partition, "read_committed");
    }

    /** read_uncommitted 관점의 끝 오프셋(HW, High Watermark)을 반환한다. */
    static long highWatermark(String topic, int partition) {
        return endOffset(topic, partition, "read_uncommitted");
    }

    private static long endOffset(String topic, int partition, String isolationLevel) {
        TopicPartition tp = new TopicPartition(topic, partition);
        try (KafkaConsumer<String, String> consumer = consumer("offset-probe-" + System.nanoTime(), isolationLevel)) {
            consumer.assign(List.of(tp));
            return consumer.endOffsets(List.of(tp)).get(tp);
        }
    }

    // ── 출력 헬퍼 ──────────────────────────────────────────────────

    static void printSeparator() {
        System.out.println("  " + "-".repeat(60));
    }

    static void printHeader(String title, String point) {
        System.out.println("\n" + "=".repeat(62));
        System.out.println("  " + title);
        System.out.println("  핵심: " + point);
        System.out.println("=".repeat(62));
    }

    static void printRecords(String label, List<ConsumerRecord<String, String>> records) {
        System.out.printf("  [%-18s] %d건%n", label, records.size());
        for (ConsumerRecord<String, String> r : records) {
            System.out.printf("      offset=%-4d key=%-10s value=%s%n", r.offset(), r.key(), r.value());
        }
    }
}
