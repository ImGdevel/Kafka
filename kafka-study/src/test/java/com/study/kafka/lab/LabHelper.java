package com.study.kafka.lab;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 실습용 공통 유틸리티.
 * Docker Compose 3-broker KRaft 클러스터를 대상으로 한다.
 */
class LabHelper {

    static final String BOOTSTRAP_SERVERS = "localhost:9092,localhost:9094,localhost:9095";

    /** Docker Kafka가 실행 중인지 간단히 확인한다. */
    static boolean isKafkaAvailable() {
        try (Socket ignored = new Socket("localhost", 9092)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 토픽을 생성한다. 이미 존재하면 삭제 후 재생성한다.
     * auto.create.topics.enable=false 환경이므로 AdminClient가 필요하다.
     */
    static void createTopic(String name, int partitions, short replication) throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(props)) {
            try {
                admin.deleteTopics(List.of(name)).all().get();
                Thread.sleep(1000); // 삭제 완료 대기
            } catch (Exception ignored) {}

            NewTopic topic = new NewTopic(name, partitions, replication);
            admin.createTopics(List.of(topic)).all().get();
            Thread.sleep(500); // 생성 완료 대기
        }
    }

    /** 토픽을 삭제한다. 없으면 무시한다. */
    static void deleteTopic(String name) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.deleteTopics(List.of(name)).all().get();
        } catch (Exception ignored) {}
    }

    /**
     * 토픽의 메시지를 처음부터 수신한다.
     * expectedCount개를 받거나 timeoutMs가 초과되면 반환한다.
     */
    static List<ConsumerRecord<String, String>> consumeAll(
            String topic, String groupId, int expectedCount, long timeoutMs) {

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (records.size() < expectedCount && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    /** 처리량 결과를 표준 형식으로 출력한다. */
    static void printResult(String label, int count, long elapsedMs) {
        long rate = count * 1000L / Math.max(elapsedMs, 1);
        System.out.printf("  [%-25s] %,d msgs / %4dms → %,8d msg/sec%n",
                label, count, elapsedMs, rate);
    }

    /** 구분선 출력용 헬퍼 */
    static void printSeparator() {
        System.out.println("  " + "-".repeat(60));
    }

    /** 메트릭 값을 이름으로 찾아 반환한다. 없으면 0.0 */
    static double getMetric(Map<org.apache.kafka.common.MetricName,
            ? extends org.apache.kafka.common.Metric> metrics, String name) {
        return metrics.entrySet().stream()
                .filter(e -> e.getKey().name().equals(name))
                .findFirst()
                .map(e -> {
                    Object val = e.getValue().metricValue();
                    return val instanceof Number ? ((Number) val).doubleValue() : 0.0;
                })
                .orElse(0.0);
    }
}
