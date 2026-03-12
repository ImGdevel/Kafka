# Kafka 클러스터링

## 1. 배경과 목표

기존 단일 KRaft 브로커 환경을 **3-브로커 클러스터**로 확장해 아래 개념을 직접 실험한다.

- KRaft 쿼럼 기반 리더 선출
- ISR(In-Sync Replica) 관리와 min.insync.replicas 동작
- unclean.leader.election 비활성화에 따른 데이터 일관성 우선 전략
- 멀티 파티션 환경에서의 메시지 순서 보장 (key 기반 파티셔닝)
- CooperativeStickyAssignor 기반 컨슈머 그룹 리밸런싱

---

## 2. 클러스터 구성

### 2.1 브로커 정보

| 브로커 | NODE_ID | 호스트 포트 | Docker 내부 포트 |
|---|---|---|---|
| kafka-1 | 1 | `localhost:9092` | `kafka-1:29092` |
| kafka-2 | 2 | `localhost:9094` | `kafka-2:29092` |
| kafka-3 | 3 | `localhost:9095` | `kafka-3:29092` |

모든 브로커는 `broker + controller` 역할을 겸한다 (KRaft combined mode).

### 2.2 리스너 구조

```
KAFKA_LISTENERS=INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka-N:29092,EXTERNAL://localhost:909X
KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL
KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER
```

- **INTERNAL**: 브로커 간 복제, Spring 앱(Docker 내부)
- **EXTERNAL**: 호스트 머신에서 직접 접근 (테스트/검증용)
- **CONTROLLER**: KRaft 쿼럼 투표 전용

### 2.3 핵심 브로커 설정

| 설정 | 값 | 이유 |
|---|---|---|
| `KAFKA_CLUSTER_ID` | `_ylp8HbfTJupBfiKFBj97A` | 3개 브로커 동일 값 필수 |
| `KAFKA_CONTROLLER_QUORUM_VOTERS` | `1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093` | 홀수 쿼럼 → split-brain 방지 |
| `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR` | `3` | `__consumer_offsets` 내부 토픽 RF |
| `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` | `3` | `__transaction_state` 내부 토픽 RF |
| `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR` | `2` | 트랜잭션 상태 로그 최소 ISR |
| `KAFKA_MIN_INSYNC_REPLICAS` | `2` | 브로커 기본 최소 ISR |
| `KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE` | `false` | ISR 외 브로커 리더 승격 차단 |
| `KAFKA_AUTO_CREATE_TOPICS_ENABLE` | `false` | 토픽은 KafkaConfig에서 명시 생성 |

---

## 3. 토픽 설정

### 3.1 study-topic / study-topic-dlt

`KafkaConfig.java` 기준:

| 항목 | 값 |
|---|---|
| 파티션 수 | 3 |
| Replication Factor | 3 |
| min.insync.replicas | 2 |

### 3.2 notification 토픽 4종

`NotificationKafkaTopicConfig.java` 기준 (`notification.requested`, `notification.sent`, `notification.failed`, `notification.requested.dlt`):

| 항목 | 값 |
|---|---|
| 파티션 수 | 3 (기존: 1) |
| Replication Factor | 3 (기존: 1) |
| min.insync.replicas | 2 |

---

## 4. 해결하는 문제

### 4.1 리더 파티션 승격 문제 (unclean.leader.election)

**문제**: ISR에 없는 팔로워가 리더가 되면 커밋되지 않은 메시지가 유실된다.

**해결**: `KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false`

```
정상 시나리오 (ISR: broker-1, 2, 3):
  broker-1 다운 → ISR 잔여: broker-2, broker-3 (2개)
  min.insync.replicas=2 충족 → broker-2가 새 리더로 선출
  acks=all 메시지는 이미 broker-2에 복제 완료 → 유실 없음

이중 장애 시나리오 (ISR: 1개만 남은 경우):
  min.insync.replicas=2 미충족 → NotEnoughReplicasException
  → 프로듀싱 차단 (데이터 유실보다 에러 반환 선택)
```

### 4.2 메시지 순서 보장 문제 (멀티 파티션)

**원칙**: Kafka는 파티션 내 순서만 보장한다. 전역(토픽 레벨) 순서는 보장하지 않는다.

```
파티션 0: msg-A → msg-D   (이 순서는 보장)
파티션 1: msg-B → msg-E   (이 순서는 보장)
파티션 2: msg-C → msg-F   (이 순서는 보장)
컨슈머 소비: A, B, C, D, E, F ... (비결정적)
```

**전략**: `notificationId`(엔티티 ID)를 key로 사용 → 동일 key → 동일 파티션 → 파티션 내 순서 보장

#### 현재 구현 확인

| 발행 지점 | 코드 | key |
|---|---|---|
| `OutboxRelay.relay()` | `ops.send(topic, outbox.getNotificationId(), event)` | `notificationId` |
| `NotificationWorkerService` (성공) | `kafkaTemplate.send(SENT, event.notificationId(), ...)` | `notificationId` |
| `NotificationWorkerService` (실패) | `kafkaTemplate.send(FAILED, event.notificationId(), ...)` | `notificationId` |

모든 발행 지점에서 `notificationId`를 key로 사용하므로 **동일 알림의 이벤트는 항상 같은 파티션**에 배정된다.

#### 보장되는 것과 보장되지 않는 것

**보장됨**: 동일 `notificationId`로 발행된 메시지(예: Outbox 재처리 시 재발행)는 같은 파티션 → 같은 컨슈머 스레드가 오프셋 순서대로 소비

```
notification.requested 파티션 1:
  REQUESTED(notificationId=AAA, seq=1)
  REQUESTED(notificationId=AAA, seq=2, 재발행)  ← Outbox 재시도
  → 같은 파티션이므로 seq 순서 보장
  → Pillar 3 멱등 게이트가 seq=2를 스킵
```

**보장 불필요**: `REQUESTED → SENT`는 서로 다른 토픽이므로 파티션 순서 문제가 아님.
Worker가 REQUESTED를 소비한 후에만 SENT를 발행하므로 비즈니스 흐름 자체가 순서를 보장.

#### 순서 보장이 깨지는 경우

- key 없이 발행(라운드로빈) → 동일 엔티티 이벤트가 다른 파티션에 배정 → 순서 보장 불가
- 같은 파티션이라도 컨슈머가 `concurrency > 파티션 수`이면 같은 파티션에 복수 스레드가 붙을 수 있음 → 현재 `concurrency=3 = 파티션 수`이므로 안전

> **전역 순서가 필요한 경우**: 파티션 수를 1로 줄여야 한다. 단, 처리량(throughput)을 희생한다.

### 4.3 컨슈머 그룹 리밸런싱

**Eager Rebalancing (기존)**: 리밸런싱 시작 시 모든 컨슈머가 파티션을 전부 반납 → Stop-The-World

**CooperativeStickyAssignor (현재)**: 이동이 필요한 파티션만 점진적으로 재할당

- `kafka-study`: `partition-assignment-strategy: CooperativeStickyAssignor` 이미 설정
- `notification-worker-app`: `concurrency: 3` = 파티션 수 3 → 컨슈머 스레드와 파티션 1:1 최적 배정

### 4.4 KRaft Split-brain 방지

```
3개 컨트롤러 중 과반(2개) 투표 시에만 리더 선출 가능
  → 네트워크 분할로 1개 브로커 고립 시:
     고립 브로커 (1표) → 과반 미달 → 리더 선출 불가
     나머지 2개 브로커 (2표) → 과반 달성 → 기존 리더 유지
```

---

## 5. bootstrap-servers 변경

클라이언트는 목록 중 아무 브로커 1개에 접속해 전체 클러스터 메타데이터를 가져온다.
여러 주소를 등록하면 1개 브로커 다운 시에도 나머지로 bootstrap이 성공한다.

| 환경 | bootstrap-servers |
|---|---|
| 호스트 (로컬 실행) | `localhost:9092,localhost:9094,localhost:9095` |
| Docker 내부 | `kafka-1:29092,kafka-2:29092,kafka-3:29092` |

적용 파일:
- `kafka-study/src/main/resources/application.yaml`
- `notification-producer-app/src/main/resources/application.yaml`
- `notification-worker-app/src/main/resources/application.yaml`

---

## 6. 기동 절차

```bash
# 1. 기존 단일 브로커 볼륨 삭제 (볼륨 충돌 방지 — 필수)
docker compose down -v

# 2. 3-브로커 클러스터 기동
docker compose up -d

# 3. 앱 기동 (토픽 자동 생성)
./gradlew :kafka-study:bootRun
```

> 기존 `kafka_data` 볼륨에 단일 브로커 메타데이터가 남아있으면 CLUSTER_ID 불일치로 기동 실패한다.
> 반드시 `docker compose down -v`로 볼륨까지 삭제한 뒤 재기동한다.

---

## 7. 검증 명령

```bash
# KRaft quorum 상태 (리더/팔로워/뒤처진 브로커 확인)
docker exec kafka-1 /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server localhost:9092 describe --status

# 토픽 상세: RF=3, ISR=3, min.insync.replicas=2 확인
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic study-topic

# 리더 선출 실험: broker-1 강제 종료
docker stop kafka-1
docker exec kafka-2 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9094 --describe --topic study-topic
# → Leader 컬럼이 2 또는 3으로 변경됨

# broker-1 복구 후 ISR 복원 확인
docker start kafka-1
docker exec kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic study-topic
# → ISR 컬럼이 다시 0,1,2 (3개)로 복원됨

# 컨슈머 그룹 파티션 할당 확인
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group notification-worker-group

# 메시지 발행/소비 테스트 (POST → GET 상태 확인)
curl -X POST http://localhost:8082/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"channel":"EMAIL","templateCode":"WELCOME","target":"test@test.com","content":"hello"}'
```

---

## 8. 추가 고려사항

클러스터 구성 이후 추가로 고려해야 할 문제들. 각 항목별 상세 문서 참조.

| 문제 | 발생 조건 | 코드 적용 여부 | 상세 문서 |
|---|---|---|---|
| DLT 파티션 라우팅 | 원본 토픽과 DLT 토픽 파티션 수 불일치 | 적용 (`-1` 라우팅) | [kafka-clustering-dlt-partition.md](./kafka-clustering-dlt-partition.md) |
| 컨슈머 리밸런싱 | 인스턴스 추가/제거/재시작 시 | 적용 (CooperativeStickyAssignor) | [kafka-clustering-rebalancing.md](./kafka-clustering-rebalancing.md) |
| 프로듀서 Epoch 충돌 | 동일 transactional.id 다중 인스턴스 | 문서화만 (현재 단일 인스턴스) | [kafka-clustering-producer-epoch.md](./kafka-clustering-producer-epoch.md) |
| 컨슈머 Lag 모니터링 | 처리 속도 < 발행 속도 누적 | 문서화만 (수동 명령 제공) | [kafka-clustering-consumer-lag.md](./kafka-clustering-consumer-lag.md) |

---

## 9. 예상 이슈와 해결

| 이슈 | 원인 | 해결 |
|---|---|---|
| 브로커 간 연결 실패 | 기존 볼륨에 단일 브로커 메타 잔존 | `docker compose down -v` |
| CLUSTER_ID 불일치 오류 | 3개 브로커에 다른 값이 입력된 경우 | `docker-compose.yml`에서 동일 22자 UUID 확인 |
| `NotEnoughReplicasException` | 브로커 기동 전 앱이 발행 시도 | healthcheck + `depends_on: condition: service_healthy` |
| 토픽 설정 변경 미반영 | Spring은 기존 토픽을 수정하지 않음 | `docker compose down -v` 후 재기동 |
| broker-2개 동시 다운 후 produce 실패 | min.insync.replicas=2 미충족 | 의도된 동작 (데이터 일관성 우선) |
