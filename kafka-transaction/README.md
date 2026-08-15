# kafka-transaction

Kafka 트랜잭션(Exactly-Once Semantics) 학습 전용 모듈. `kafka-connect` 모듈과 동일하게 **테스트 전용**이며, 실습 코드는 모두 `src/test`에 있다.

## 전제 환경

루트 `docker-compose.yml`의 3-broker KRaft 클러스터를 그대로 사용한다. 트랜잭션에 필요한 설정은 이미 들어가 있다.

| 설정 | 값 | 의미 |
|---|---|---|
| `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` | 3 | `__transaction_state` 복제본 수 |
| `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR` | 2 | 트랜잭션 상태 로그 최소 ISR |
| `KAFKA_MIN_INSYNC_REPLICAS` | 2 | 일반 토픽 최소 ISR |

브로커 3대 중 1대가 죽어도 트랜잭션 코디네이터가 계속 동작하는 구성이다.

## 실행

```bash
docker compose up -d
```

```bash
./gradlew :kafka-transaction:test --tests '*Lab00*' --info
```

전체 실습을 순서대로 돌리려면:

```bash
./gradlew :kafka-transaction:test --info
```

Kafka가 안 떠 있으면 `assumeTrue`로 테스트가 실패가 아니라 skip 된다.

## 실습 구성

| Lab | 주제 | 검증 명제 | 상태 |
|---|---|---|---|
| Lab00 | 환경 점검 | 이 클러스터에서 트랜잭션 실습을 할 수 있다 | 완료 |
| Lab01 | 좀비 펜싱 | 같은 `transactional.id`의 이전 인스턴스는 자동으로 무력화된다 | 완료 |
| Lab02 | `isolation.level`과 LSO | 긴 트랜잭션은 같은 파티션의 무관한 메시지까지 막는다 | 완료 |
| Lab03 | consume-transform-produce | 출력 produce와 입력 offset commit이 하나의 원자 단위가 된다 | 완료 |
| Lab04 | `transaction.timeout.ms` | 트랜잭션을 열어둔 채 방치하면 코디네이터가 대신 끝낸다 | 예정 |
| Lab05 | Spring 추상화 | Spring Kafka의 트랜잭션은 Lab01~04와 같은 메커니즘 위에 있다 | 예정 |
| Lab06 | 트랜잭션의 한계 | Kafka 트랜잭션은 Kafka 안에서만 원자적이다 | 예정 |
| Lab07 | EOS의 비용 | 정확히 한 번은 공짜가 아니다 — 트랜잭션 경계 설계가 성능을 좌우한다 | 예정 |
| Lab08 | 장애 내성 | 트랜잭션은 코디네이터와 min ISR에 의존한다 | 예정 |

Lab01 → 02 → 03이 핵심 축이다. Lab04는 Lab02의 후속(막힌 LSO가 언제 풀리는가), Lab05는 Lab03의 Spring판이다.

### 각 Lab이 검증하는 것

**Lab01 — 좀비 펜싱** (`Lab01ZombieFencingTest`)
- Q1. 같은 txId로 A → B 순서로 `initTransactions()` → 뒤늦게 쓰려는 A가 `ProducerFencedException` / `InvalidProducerEpochException`을 받고, A의 메시지는 `read_committed`에 안 보인다
- Q2. `initTransactions()`가 이전 인스턴스의 미완결 트랜잭션을 **abort로 정리**해 LSO를 풀어준다
- Q3. (대조군) txId 없는 Producer 2개는 서로 펜싱되지 않는다 → 펜싱은 txId에 붙은 epoch의 기능

**Lab02 — `isolation.level`과 LSO** (`Lab02IsolationLevelTest`)
- Q1. A가 트랜잭션을 열어둔 채 B가 같은 파티션에 커밋 완료 → `read_committed`는 **0건**. LSO가 A의 시작 오프셋에 묶인다. A를 끝내야 3건이 한꺼번에 쏟아진다
- Q2. 같은 순간 `read_uncommitted`는 3건을 즉시 본다. A를 abort하면 B의 메시지가 offset 2에 그대로 남아 있다 — 막혔던 것이지 사라진 게 아니다
- Q3. 트랜잭션 3회 × 메시지 2건 → 끝 오프셋 9. `끝 오프셋 − 메시지 수 = 커밋한 트랜잭션 수`. consumer lag이 0으로 안 떨어져 보이는 착시의 산술적 근거

**Lab03 — consume-transform-produce** (`Lab03ConsumeTransformProduceTest`)
- Q1. `sendOffsetsToTransaction(offsets, consumer.groupMetadata())` 커밋 → 출력 3건 노출 + 입력 그룹 committed offset이 3으로 전진
- Q2. 커밋 직전 abort → 출력도 안 보이고 오프셋도 전진 안 함. 되감아 재처리하면 출력은 **정확히 1건**
- Q3. (대조군) `auto.commit`은 애플리케이션 로직과 무관한 시점에 발화 → 오프셋만 커밋되고 출력은 없는 상태가 만들어진다 = **유실**

## 공통 유틸 (`TxHelper`)

- `transactionalProducer(txId[, overrides])` — `transactional.id` + 멱등성 Producer
- `readCommitted(...)` / `readUncommitted(...)` — `isolation.level`별 소비
- `lastStableOffset(...)` / `highWatermark(...)` — 진행 중 트랜잭션이 LSO를 붙잡는 현상 관측
- `describeTransaction(txId)` / `listTransactions()` — AdminClient로 트랜잭션 상태 조회
- `createTopic(...)` / `topicConfig(...)` — 토픽 생성 및 설정 조회

## 핵심 개념 메모

- 중단(abort)된 메시지도 **로그에는 물리적으로 남는다**. 걸러내는 주체는 브로커가 아니라 `read_committed` 소비자다.
- 커밋/중단 마커(control batch)는 오프셋을 1칸 차지한다. 메시지 2개를 커밋하면 끝 오프셋은 3이 된다.
- 진행 중인 트랜잭션은 LSO를 붙잡으므로, `read_committed` 소비자는 트랜잭션이 끝날 때까지 그 뒤 메시지를 못 본다 → 긴 트랜잭션은 곧 소비 지연이다.
