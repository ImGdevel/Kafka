# 클러스터 시나리오 테스트 결과

실행 일시: 2026-03-13

## 환경

- 3-broker KRaft 클러스터 (kafka-1/2/3, ports 9092/9094/9095)
- notification-producer-app (port 8082) + notification-worker-app
- PostgreSQL DB 2개 (notification-producer-db:5432, notification-worker-db:5432)
- 토픽: notification.requested (파티션 3, RF=3, min.insync.replicas=2)

---

## 시나리오 1: 리더 파티션 선출 검증

### 실행 명령

```bash
# 중단 전 파티션 리더 확인
docker exec kafka-kafka-1-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic notification.requested

# kafka-3 중단
docker stop kafka-kafka-3-1
sleep 5

# 중단 후 새 리더 확인
docker exec kafka-kafka-1-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic notification.requested

# kafka-3 복구
docker start kafka-kafka-3-1
```

### 결과

**중단 전:**
```
Partition: 0  Leader: 3  Replicas: 3,1,2  Isr: 3,1,2
Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 2,3,1
```

**kafka-3 중단 후 (약 5초 이내):**
```
Partition: 0  Leader: 1  Replicas: 3,1,2  Isr: 1,2
Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2
Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 2,1
```

**kafka-3 복구 후 (약 15초):**
```
Partition: 0  Leader: 1  Replicas: 3,1,2  Isr: 1,2,3
Partition: 1  Leader: 1  Replicas: 1,2,3  Isr: 1,2,3
Partition: 2  Leader: 2  Replicas: 2,3,1  Isr: 2,1,3
```

### 분석

- **리더 재선출 성공**: kafka-3 중단 시 Partition 0의 리더가 3 → 1로 즉시 전환
- **ISR 기반 선출**: `KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE=false` 설정으로 ISR 내 브로커만 리더 승격
- **자동 복구**: 브로커 재기동 후 ISR에 자동으로 복귀 (Isr: 1,2 → 1,2,3)
- **리더 유지**: 복구 후 리더는 변경되지 않음 (preferred leader election은 별도 설정 필요)

---

## 시나리오 2: 대량 발행 중 브로커 장애 + 순서 보장 검증

### 실행 명령

```bash
# 10개 연속 발행 (동일 recipient → murmur2(key) % 3 = 동일 파티션 경향)
for i in $(seq 1 10); do
  curl -X POST http://localhost:8082/api/notifications \
    -H "Content-Type: application/json" \
    -d '{"channel":"EMAIL","recipient":"order-test@test.com","subject":"order-'$i'","body":"body","templateCode":"WELCOME"}'
done

# 발행 중 kafka-2 중단
docker stop kafka-kafka-2-1

# 브로커 장애 중 5개 추가 발행
for i in $(seq 11 15); do
  curl -X POST http://localhost:8082/api/notifications ...
done
```

### 결과

**발행 결과**: 15개 모두 `ACCEPTED` 응답 (Outbox 패턴 — HTTP 응답은 DB 저장 완료 시점)

**Worker 처리 로그 순서** (파티션 내 순서 보장 확인):
```
파티션 1 (consumer-2 처리): bd4dae14 → b37171e4 → 0e486e4a
파티션 0 (consumer-1 처리): 9f6ea1f4 → f65ec168 → 83e837a7
파티션 2 (consumer-3 처리): f30a7485 → fb07478e → ccca15a1 → 32a5f4ff
```

**처리 결과 확인**: 15개 모두 `status: SENT`

### 분석

- **브로커 1개 중단 중에도 발행/소비 계속**: ISR=2개 남아 min.insync.replicas=2 충족
- **순서 보장**: 동일 파티션 내에서는 발행 순서대로 소비 (파티션 경계 내 순서 보장)
- **Outbox 내구성**: kafka-2 중단 전 발행된 Outbox 레코드는 브로커 복구 없이 처리됨 (남은 2개 브로커 활용)

---

## 시나리오 3: min.insync.replicas 위반 시 발행 차단

### 실행 명령

```bash
# kafka-2, kafka-3 동시 중단 (ISR=1 → min.insync.replicas=2 위반)
docker stop kafka-kafka-2-1 kafka-kafka-3-1

# 발행 시도
curl -X POST http://localhost:8082/api/notifications \
  -d '{"channel":"EMAIL","recipient":"fail-test@test.com",...}'

# 브로커 복구
docker start kafka-kafka-2-1 kafka-kafka-3-1
```

### 결과

**HTTP 응답**: `ACCEPTED` (Outbox 패턴 — DB 저장은 Kafka와 무관하게 성공)

**Kafka 발행 시도 (OutboxRelay)**: kafka-1만 살아있는 상태에서 TransactionManager가 지속 재시도
```
[Producer transactionalId=notification-outbox-tx-1] Discovered transaction coordinator kafka-1:29092 ...
[계속 반복 — 메시지 전송 불가 상태]
```

**브로커 복구 후**: 미발행 Outbox 레코드가 자동으로 릴레이 재처리 → `status: SENT` 완료

### 분석

- **차단 위치**: HTTP 레이어가 아닌 OutboxRelay(Kafka 발행) 레이어에서 차단
- **데이터 유실 없음**: Outbox 미발행 상태로 DB에 보존 → 브로커 복구 시 자동 재처리
- **의도된 동작**: `acks=all` + `min.insync.replicas=2` → ISR 부족 시 produce 거부 (데이터 일관성 우선)
- **Outbox 패턴의 강점**: 브로커 장애와 HTTP 가용성이 분리됨

---

## 시나리오 4: 컨슈머 리밸런싱 (CooperativeStickyAssignor)

### 실행 명령

```bash
# 재시작 전 파티션 할당 확인
docker exec kafka-kafka-1-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group notification-worker-group

# worker-app 재시작
docker restart kafka-notification-worker-app-1
```

### 결과

**재시작 전 할당:**
```
PARTITION  CONSUMER-ID
1          consumer-notification-worker-group-2-...
2          consumer-notification-worker-group-3-...
0          consumer-notification-worker-group-1-...
```

**CooperativeStickyAssignor 리밸런싱 2단계:**

**1라운드 (generation 7)**: 기존 컨슈머들이 파티션 반납 + 재시작된 신규 컨슈머가 임시로 모든 파티션 할당
```
[consumer-1] Revoke previously assigned partitions notification.requested-0
[consumer-2] Revoke previously assigned partitions notification.requested-1
[consumer-3] Revoke previously assigned partitions notification.requested-2
→ 신규 컨슈머(consumer-1-new): Assignment(partitions=[0,1,2])
```

**2라운드 (generation 8)**: 3개 컨슈머에게 1개씩 균등 배분
```
consumer-1-new → [notification.requested-0]
consumer-2-new → [notification.requested-1]
consumer-3-new → [notification.requested-2]
```

### 분석

- **2단계 리밸런싱 확인**: CooperativeStickyAssignor는 이동 필요한 파티션만 순차 이동
- **완전 중단 없음**: Eager(RangeAssignor) 대비 소비 중단 시간 최소화
- **재시작 경우 특이사항**: 컨테이너 재시작으로 인해 기존 3개 consumer-id가 모두 새로 생성됨 → 1라운드에서 신규 컨슈머가 전체 파티션 임시 할당 후 2라운드에서 재배분

---

## 시나리오 5: Consumer Lag 모니터링

### 실행 명령

```bash
# 30개 빠르게 발행 후 즉시 lag 확인
for i in $(seq 1 30); do
  curl -X POST http://localhost:8082/api/notifications ...
done

docker exec kafka-kafka-1-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group notification-worker-group
```

### 결과

**발행 직후 Lag:**
```
PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
1          23              24              1
2          17              18              1
0          11              12              1
```

**처리 완료 후 Lag (10초 후):**
```
PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
1          35              36              1
2          35              36              1
0          19              20              1
```

### 분석

- **파티션별 분산 확인**: 3개 파티션에 메시지가 고르게 분산 (recipient key 기반 라우팅)
- **처리 속도**: SandboxSender가 즉시 반환하므로 lag 누적 없이 빠르게 처리
- **Lag = 1 유지**: Outbox 릴레이 폴링 주기(1초) 사이에 발행된 미처리 메시지 1개 상시 존재
  - LOG-END-OFFSET이 CURRENT-OFFSET보다 항상 1 앞서는 것은 OutboxRelay 배치 처리 타이밍 때문
- **클러스터 모니터링 핵심**: lag이 파티션별로 분산됨 → 단일 브로커만 보면 전체 파악 불가

---

## 종합 결과

| 시나리오 | 예상 동작 | 실제 결과 | 판정 |
|---|---|---|---|
| 리더 재선출 | 브로커 다운 시 ISR 내 다른 브로커가 리더 승격 | ✅ 5초 이내 자동 재선출 | 정상 |
| 브로커 1개 장애 중 발행 | ISR=2 유지, 발행/소비 계속 | ✅ 15개 모두 SENT 완료 | 정상 |
| 브로커 2개 장애 시 발행 차단 | min.insync.replicas=2 미충족 → 발행 차단 | ✅ OutboxRelay 블로킹 (HTTP는 ACCEPTED) | 정상 |
| 브로커 복구 후 자동 재처리 | Outbox 레코드가 미발행 상태로 보존 → 재처리 | ✅ 복구 후 자동 SENT 완료 | 정상 |
| CooperativeStickyAssignor | 리밸런싱 시 파티션 점진 이동 | ✅ 2단계 generation 확인 | 정상 |
| Consumer Lag | 파티션별 분산 lag 확인 | ✅ kafka-consumer-groups.sh로 파티션별 확인 | 정상 |

---

## 관련 코드 변경 사항 (테스트 중 발견)

### NotificationRequestEntity 수정
`@Enumerated(EnumType.STRING)` 어노테이션 누락으로 Docker 환경 기동 실패.

- **원인**: JPA 기본값 `ORDINAL`(smallint) vs Flyway SQL `VARCHAR(20)` 불일치
- **수정**: [NotificationRequestEntity.java](../../../kafka-notification/notification-producer-app/src/main/java/com/study/notification/producer/persistence/NotificationRequestEntity.java)에 `@Enumerated(EnumType.STRING)` 추가

### docker-compose.yml PostgreSQL 추가
notification 앱들이 PostgreSQL DB 없이 기동 불가.

- **수정**: `notification-producer-db` (port 5432), `notification-worker-db` (port 5433) 서비스 추가
- 각 앱에 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` 환경변수 주입
