package com.study.kafka.connect;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Kafka Connect 실습용 공통 유틸리티.
 * Connect REST API, Kafka AdminClient, docker exec 기능을 제공한다.
 */
class ConnectHelper {

    static final String CONNECT_URL = "http://localhost:8083";
    static final String BOOTSTRAP   = "localhost:9092,localhost:9094,localhost:9095";
    static final String CONTAINER   = "kafka-connect-1";
    static final String DATA_DIR    = "/tmp/connect-data";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ── 가용성 체크 ────────────────────────────────────────────────

    static boolean isConnectAvailable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 8083), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isKafkaAvailable() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 9092), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Connect REST API ───────────────────────────────────────────

    static String getJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONNECT_URL + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    static String postJson(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONNECT_URL + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Connect API error " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    static void putEmpty(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONNECT_URL + path))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HTTP.send(req, HttpResponse.BodyHandlers.discarding());
    }

    static void deleteConnector(String name) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CONNECT_URL + "/connectors/" + name))
                    .DELETE()
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    /**
     * 커넥터를 STOP → 오프셋 초기화 → 삭제한다.
     * 이전 테스트 실행의 stale connect-offsets가 다음 실행에 영향을 주는 것을 방지.
     * Kafka Connect 3.6+의 PUT /stop, DELETE /offsets API 사용.
     */
    static void cleanConnector(String name) {
        // RUNNING → STOPPED (오프셋 삭제를 위한 전제 조건)
        try {
            putEmpty("/connectors/" + name + "/stop");
            Thread.sleep(1500);
        } catch (Exception ignored) {}
        // 커넥터 오프셋 초기화 (connect-offsets 토픽의 stale 항목 제거)
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CONNECT_URL + "/connectors/" + name + "/offsets"))
                    .DELETE()
                    .build();
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
        deleteConnector(name);
    }

    /**
     * Source Connector의 파일 오프셋을 connect-offsets 토픽에 tombstone으로 직접 초기화한다.
     * REST API 방식과 달리 커넥터 존재 여부와 무관하게 항상 동작한다.
     * 이전 테스트 실행에서 남은 stale offset을 제거하는 데 사용한다.
     *
     * connect-offsets 키 형식: ["connectorName", {"filename": "path"}]
     */
    static void resetSourceOffset(String connectorName, String filename) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        String key = "[\"" + connectorName + "\",{\"filename\":\"" + filename + "\"}]";
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("connect-offsets", key, null)).get();
            producer.flush();
        } catch (Exception e) {
            System.err.println("  [WARNING] connect-offsets tombstone 전송 실패: " + e.getMessage());
        }
    }

    static void pauseConnector(String name) throws Exception {
        putEmpty("/connectors/" + name + "/pause");
    }

    static void resumeConnector(String name) throws Exception {
        putEmpty("/connectors/" + name + "/resume");
    }

    static void restartConnector(String name) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONNECT_URL + "/connectors/" + name + "/restart"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HTTP.send(req, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * 커넥터의 현재 상태를 반환한다.
     * GET /connectors/{name}/status → connector.state 값
     * 커넥터 상태가 RUNNING이어도 태스크 중 하나라도 FAILED면 "FAILED" 반환.
     */
    static String getConnectorState(String name) throws Exception {
        String response = getJson("/connectors/" + name + "/status");
        int idx = response.indexOf("\"connector\"");
        if (idx < 0) return "UNKNOWN";
        int stateIdx = response.indexOf("\"state\":\"", idx);
        if (stateIdx < 0) return "UNKNOWN";
        int start = stateIdx + 9;
        int end = response.indexOf('"', start);
        String connState = response.substring(start, end);
        if ("RUNNING".equals(connState)) {
            int tasksIdx = response.indexOf("\"tasks\"");
            if (tasksIdx >= 0) {
                String tasksPart = response.substring(tasksIdx);
                int tsIdx = tasksPart.indexOf("\"state\":\"");
                while (tsIdx >= 0) {
                    int ts = tsIdx + 9;
                    int te = tasksPart.indexOf('"', ts);
                    if ("FAILED".equals(tasksPart.substring(ts, te))) {
                        return "FAILED";
                    }
                    tsIdx = tasksPart.indexOf("\"state\":\"", te);
                }
            }
        }
        return connState;
    }

    /**
     * 커넥터가 expectedState에 도달할 때까지 폴링한다.
     * timeoutMs 초과 시 RuntimeException.
     */
    static void waitForState(String name, String expectedState, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        System.out.print("  대기 중");
        while (System.currentTimeMillis() < deadline) {
            try {
                String state = getConnectorState(name);
                if (expectedState.equals(state)) {
                    System.out.println();
                    return;
                }
            } catch (Exception ignored) {}
            System.out.print(".");
            Thread.sleep(1000);
        }
        System.out.println();
        throw new RuntimeException("Connector " + name + " 이 " + timeoutMs + "ms 내에 " + expectedState + " 상태에 도달하지 못했습니다");
    }

    /**
     * GET /connectors/{name}/tasks 로 실제 실행 중인 task 수를 반환한다.
     */
    static int getTaskCount(String name) throws Exception {
        String response = getJson("/connectors/" + name + "/tasks");
        // 배열: [{...}, {...}] → 배열 원소 수 카운팅
        int count = 0;
        int idx = 0;
        while ((idx = response.indexOf("\"id\"", idx)) >= 0) {
            count++;
            idx++;
        }
        return count;
    }

    // ── connect-offsets 토픽 읽기 ──────────────────────────────────

    /**
     * connect-offsets 토픽에서 주어진 커넥터 이름을 포함하는 레코드를 반환한다.
     */
    static List<ConsumerRecord<String, String>> readConnectOffsets(String connectorName, long timeoutMs) {
        List<ConsumerRecord<String, String>> all =
                consumeUntilTimeout("connect-offsets", "lab-offset-reader-" + System.nanoTime(), timeoutMs);
        List<ConsumerRecord<String, String>> filtered = new ArrayList<>();
        for (ConsumerRecord<String, String> r : all) {
            if (r.key() != null && r.key().contains(connectorName)) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    // ── Docker exec 유틸 ───────────────────────────────────────────

    static String runOnConnect(String... cmd) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            command.add("exec");
            command.add(CONTAINER);
            command.addAll(Arrays.asList(cmd));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(15, TimeUnit.SECONDS);
            return out.trim();
        } catch (Exception e) {
            return "[docker exec 실패: " + e.getMessage() + "]";
        }
    }

    /**
     * Connect 컨테이너 내 파일에 내용을 쓴다 (덮어쓰기).
     */
    static void writeToConnectFile(String containerPath, String content) {
        ensureConnectDir(containerPath.substring(0, containerPath.lastIndexOf('/')));
        try {
            List<String> cmd = List.of(
                    "docker", "exec", "-i", CONTAINER,
                    "sh", "-c", "cat > " + containerPath
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().write(content.getBytes());
            p.getOutputStream().close();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("파일 쓰기 실패: " + containerPath, e);
        }
    }

    /**
     * Connect 컨테이너 내 파일에 내용을 추가한다 (append).
     */
    static void appendToConnectFile(String containerPath, String content) {
        try {
            List<String> cmd = List.of(
                    "docker", "exec", "-i", CONTAINER,
                    "sh", "-c", "cat >> " + containerPath
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().write(content.getBytes());
            p.getOutputStream().close();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("파일 추가 실패: " + containerPath, e);
        }
    }

    static String readConnectFile(String containerPath) {
        return runOnConnect("cat", containerPath);
    }

    static int countConnectFileLines(String containerPath) {
        String result = runOnConnect("sh", "-c",
                "[ -f " + containerPath + " ] && wc -l < " + containerPath + " || echo 0");
        try {
            return Integer.parseInt(result.trim().split("\\s+")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    static void ensureConnectDir(String dirPath) {
        runOnConnect("mkdir", "-p", dirPath);
    }

    static void removeConnectFile(String containerPath) {
        runOnConnect("rm", "-f", containerPath);
    }

    // ── Kafka 유틸 ─────────────────────────────────────────────────

    static void createTopic(String name, int partitions, short replication) throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        try (AdminClient admin = AdminClient.create(props)) {
            try {
                admin.deleteTopics(List.of(name)).all().get();
                Thread.sleep(500);
            } catch (Exception ignored) {}
            admin.createTopics(List.of(new NewTopic(name, partitions, replication))).all().get();
            Thread.sleep(500);
        }
    }

    static void deleteTopic(String name) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.deleteTopics(List.of(name)).all().get();
        } catch (Exception ignored) {}
    }

    /**
     * 토픽에서 expectedCount 개를 받거나 timeoutMs 초과 시 반환한다.
     */
    static List<ConsumerRecord<String, String>> consumeAll(
            String topic, String groupId, int expectedCount, long timeoutMs) {
        return consume(topic, groupId, expectedCount, timeoutMs, false, "earliest");
    }

    /**
     * 최신 오프셋 이후의 새 레코드만 timeoutMs 동안 수신한다.
     * PAUSE/RESUME 시나리오에서 "일시정지 중 새 메시지 없음" 검증에 사용.
     */
    static List<ConsumerRecord<String, String>> consumeNew(
            String topic, String groupId, int maxCount, long timeoutMs) {
        return consume(topic, groupId, maxCount, timeoutMs, false, "latest");
    }

    /**
     * Consumer를 현재 파티션 끝(seekToEnd)에 위치시킨 뒤 action을 실행하고,
     * action 이후 도착하는 메시지만 수신한다.
     * RESUME 후 새로 전송된 메시지만 정확히 잡기 위해 사용.
     */
    static List<ConsumerRecord<String, String>> consumeAfterAction(
            String topic, Runnable action, int maxCount, long timeoutMs) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            var tp = new TopicPartition(topic, 0);
            consumer.assign(List.of(tp));
            consumer.seekToEnd(List.of(tp)); // 현재 끝 위치로 이동 (action 전)
            consumer.position(tp);           // 내부 offset 확정 (lazy evaluation 방지)
            action.run();                    // 여기서 RESUME (이후 도착 메시지만 읽힘)
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                if (records.size() >= maxCount) break;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return records;
    }

    /**
     * 토픽에서 timeoutMs 동안 받을 수 있는 모든 레코드를 반환한다.
     */
    static List<ConsumerRecord<String, String>> consumeUntilTimeout(
            String topic, String groupId, long timeoutMs) {
        return consume(topic, groupId, Integer.MAX_VALUE, timeoutMs, true, "earliest");
    }

    private static List<ConsumerRecord<String, String>> consume(
            String topic, String groupId, int maxCount, long timeoutMs, boolean alwaysWait,
            String offsetReset) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                if (!alwaysWait && records.size() >= maxCount) break;
            }
        }
        return records;
    }

    static void printSeparator() {
        System.out.println("  " + "-".repeat(60));
    }
}
