# Kafka 클러스터링 구현 플랜

## Context

현재 kafka 프로젝트는 단일 KRaft 브로커(broker-1)로 구성되어 있어 고가용성/내결함성이 없다.
본 작업은 3-브로커 KRaft 클러스터로 확장하여 리더 선출, ISR, 컨슈머 그룹 리밸런싱 등 Kafka 클러스터링 핵심 개념을 학습 환경에서 직접 실험할 수 있도록 구성한다.
파티션 수 증가에 따른 메시지 순서 보장 문제도 함께 점검한다.

---

## 수정 대상 파일

| 파일 | 변경 내용 |
|---|---|
| `docker-compose.yml` | 단일 브로커 → 3-브로커 KRaft 클러스터 |
| `infra-message-queue/.../KafkaConfig.java` | RF=1 → RF=3, MIN_ISR=2 추가 |
| `notification-producer-app/.../NotificationKafkaTopicConfig.java` | partition=1/RF=1 → partition=3/RF=3, MIN_ISR=2 |
| `kafka-study/.../application.yaml` | bootstrap-servers 3개로 확장 |
| `notification-producer-app/.../application.yaml` | bootstrap-servers 3개로 확장 |
| `notification-worker-app/.../application.yaml` | bootstrap-servers 3개로 확장 |

---

## 단계별 구현

### Step 1: docker-compose.yml — 3-브로커 KRaft 클러스터

기존 `kafka` 서비스를 `kafka-1`, `kafka-2`, `kafka-3`으로 교체한다.
볼륨도 각각 분리(`kafka_1_data`, `kafka_2_data`, `kafka_3_data`).

**핵심 설정값:**
```
KAFKA_CLUSTER_ID=<22자 base64 UUID>   # 3개 브로커 동일
KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093
KAFKA_PROCESS_ROLES=broker,controller   # combined mode
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=3
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=3
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=2
KAFKA_MIN_INSYNC_REPLICAS=2
KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false
KAFKA_AUTO_CREATE_TOPICS_ENABLE=false
```

**포트 매핑 (호스트 → 컨테이너):**
- kafka-1: `9092:9092`
- kafka-2: `9094:9092`
- kafka-3: `9095:9092`

**리스너 구조 (각 브로커 동일 패턴, hostname만 다름):**
```
KAFKA_LISTENERS=INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka-N:29092,EXTERNAL://localhost:909X
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER
KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL
```

**notification 서비스 (profile: notification):**
```yaml
depends_on:
  kafka-1: { condition: service_healthy }
  kafka-2: { condition: service_healthy }
  kafka-3: { condition: service_healthy }
SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka-1:29092,kafka-2:29092,kafka-3:29092
```

**healthcheck:**
```yaml
test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1"]
interval: 10s
timeout: 10s
retries: 15
```

> **CLUSTER_ID 생성 (최초 1회):**
> `docker run --rm apache/kafka:3.7.0 /opt/kafka/bin/kafka-storage.sh random-uuid`

---

### Step 2: KafkaConfig.java

```java
// 변경 상수
private static final int REPLICA_COUNT = 3;   // 기존: 1
private static final int MIN_ISR = 2;          // 신규 추가

// createTopic 메서드에 .config() 추가
private NewTopic createTopic(String topicName) {
    return TopicBuilder.name(topicName)
        .partitions(PARTITION_COUNT)
        .replicas(REPLICA_COUNT)
        .config("min.insync.replicas", String.valueOf(MIN_ISR))
        .build();
}
```

파일: `infra-message-queue/src/main/java/com/study/kafka/config/KafkaConfig.java`

---

### Step 3: NotificationKafkaTopicConfig.java

```java
// 변경 상수
private static final int PARTITION_COUNT = 3;  // 기존: 1
private static final int REPLICA_COUNT = 3;    // 기존: 1
private static final int MIN_ISR = 2;           // 신규 추가

// createTopic 메서드에 .config() 추가 (KafkaConfig와 동일 패턴)
```

파일: `kafka-notification/notification-producer-app/src/main/java/com/study/notification/producer/config/NotificationKafkaTopicConfig.java`

---

### Step 4~6: application.yaml — bootstrap-servers 변경

변경 대상 3개 파일에서 `bootstrap-servers` 값만 교체:

| 파일 | 변경 전 | 변경 후 |
|---|---|---|
| `kafka-study/application.yaml` | `localhost:9092` | `localhost:9092,localhost:9094,localhost:9095` |
| `notification-producer-app/application.yaml` | `${...:localhost:9092}` | `${...:localhost:9092,localhost:9094,localhost:9095}` |
| `notification-worker-app/application.yaml` | `${...:localhost:9092}` | `${...:localhost:9092,localhost:9094,localhost:9095}` |

---

## 해결하는 문제들

### 1. 리더 파티션 승격 문제 (unclean.leader.election)
- `KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false` + `min.insync.replicas=2`
- ISR 외 브로커가 리더가 되는 것을 차단 → 커밋되지 않은 메시지 유실 방지
- broker 1개 다운 시: ISR 잔여 2개 → 정상 리더 선출 + 프로듀싱 지속
- broker 2개 동시 다운 시: ISR 1개 → `NotEnoughReplicasException` → 프로듀싱 차단(데이터 일관성 우선)

### 2. 메시지 순서 보장 문제
- 전역 순서는 불가(멀티 파티션) → **key 기반 파티셔닝으로 파티션 내 순서 보장**
- `KafkaMessagePublisher.publish()`: key 존재 시 `ops.send(topic, key, payload)` → 이미 올바르게 구현됨
- `OutboxRelay.relay()`: `notificationId`를 key로 전송 → 동일 알림 이벤트는 항상 동일 파티션

### 3. 컨슈머 그룹 리밸런싱
- `kafka-study`: `CooperativeStickyAssignor` 이미 설정됨 (이동 필요한 파티션만 점진 재할당, Stop-The-World 없음)
- `notification-worker-app`: concurrency=3 = partition 수 3 → 최적 병렬 처리 (이미 맞춰져 있음)

### 4. KRaft Split-brain 방지
- 홀수(3개) 컨트롤러 → 과반(2개) 투표로만 리더 선출 → 네트워크 분할 시 소수 파티션은 선출 불가

---

## 적용 순서 (필수)

```bash
# 1. 기존 단일 브로커 데이터 완전 삭제 (볼륨 충돌 방지)
docker compose down -v

# 2. CLUSTER_ID 생성 (처음 한 번만)
docker run --rm apache/kafka:3.7.0 /opt/kafka/bin/kafka-storage.sh random-uuid
# 출력값을 docker-compose.yml의 KAFKA_CLUSTER_ID에 기입

# 3. 클러스터 기동
docker compose up -d

# 4. 앱 기동 (토픽 자동 생성)
./gradlew :kafka-study:bootRun
```

---

## 검증 방법

```bash
# 클러스터 quorum 상태
docker exec kafka-1 /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server localhost:9092 describe --status

# 토픽 상세 (RF=3, min.insync.replicas=2 확인)
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic study-topic

# 리더 선출 실험
docker stop kafka-1
docker exec kafka-2 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9094 --describe --topic study-topic
# Leader가 2 또는 3으로 변경 확인

# 복구 후 ISR 복원 확인
docker start kafka-1
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic study-topic

# 컨슈머 그룹 파티션 할당 확인
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group notification-worker-group
```

---

## 예상 이슈 대응

| 이슈 | 원인 | 해결 |
|---|---|---|
| 브로커 간 연결 실패 | 기존 볼륨에 단일 브로커 메타 잔존 | `docker compose down -v` |
| CLUSTER_ID 불일치 오류 | 3개 브로커에 다른 값 입력 | 동일 22자 UUID를 모든 브로커에 기재 |
| `NotEnoughReplicasException` | 브로커 기동 전 앱이 발행 시도 | healthcheck + `depends_on: condition: service_healthy` |
| 토픽 설정 변경 미반영 | Spring은 기존 토픽 수정 안 함 | `docker compose down -v` 후 재기동 |
