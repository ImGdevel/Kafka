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
| Lab04 | `transaction.timeout.ms` | 트랜잭션을 열어둔 채 방치하면 코디네이터가 대신 끝낸다 | 완료 |
| Lab05 | Spring 추상화 | Spring 추상화는 새 보장을 추가하지 않는다 | 완료 |
| Lab06 | 트랜잭션의 한계 | Kafka 트랜잭션은 Kafka 안에서만 원자적이다 | 완료 |
| Lab07 | EOS의 비용 | 비용은 메시지 수가 아니라 트랜잭션 수에 비례한다 | 완료 |
| Lab08 | 장애 내성 | 트랜잭션은 코디네이터와 min ISR에 의존한다 | 완료 ⚠ |

> ⚠ Lab08은 **브로커(kafka-3)를 정지시켰다가 복구한다.** 같은 클러스터를 쓰는 다른 작업이 있으면 먼저 중단할 것. 자세한 안전장치는 아래 참고.

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

**Lab04 — `transaction.timeout.ms`** (`Lab04TransactionTimeoutTest`)
- Q1. 타임아웃된 트랜잭션은 코디네이터가 강제 abort하면서 epoch를 올린다 → 뒤늦은 `commitTransaction()`은 실패. Lab01의 좀비와 같은 처지지만, 펜싱의 원인이 다른 Producer가 아니라 코디네이터 자신이다
- Q2. **아무도 개입하지 않아도** LSO가 저절로 풀린다 (Lab01 Q2는 새 Producer가 풀어줬다). 즉 Lab02의 head-of-line blocking은 최대 `transaction.timeout.ms + 청소 주기`만큼만 지속된다
- Q3. 브로커 `transaction.max.timeout.ms`(기본 15분)를 넘는 값은 `initTransactions()`에서 거부 → 클라이언트가 무한정 붙잡아둘 수 없다

> 대기 시간 주의: 코디네이터의 만료 청소는 즉시가 아니라 주기(기본 10초) 단위다. 고정 `sleep` 대신 LSO 전진을 폴링한다. 전체 40~50초 소요.

**Lab05 — Spring 추상화** (`Lab05SpringTransactionTest`)
- Q1. `KafkaTemplate.executeInTransaction()` — 정상 종료는 커밋, 콜백 예외는 abort. 결과는 raw Producer와 완전히 동일
- Q2. `setTransactionIdPrefix()`로 준 접두사가 실제로 코디네이터에 등록된다 (`listTransactions()`로 확인). prefix는 우리가, 접미사는 Spring이 붙인다
- Q3. `KafkaTransactionManager` + `TransactionTemplate` (= `@Transactional`이 타는 경로) — 3건 send 후 예외 → 전부 롤백. 커밋 시 `HW=4`(메시지 3 + 마커 1)로 Lab02 Q3의 마커 산술과 일치

> Spring 컨텍스트를 띄우지 않는다. 이 모듈은 `src/main`이 없어 `@SpringBootTest`가 `@SpringBootConfiguration`을 못 찾는다. 객체를 직접 조립하는 편이 검증 대상도 선명하다.

**Lab06 — 트랜잭션의 한계** (`Lab06ExternalSystemLimitTest`)
- Q1. Kafka 먼저 커밋 → 외부 커밋 실패 = Kafka에만 있는 고아 메시지. 이미 커밋된 트랜잭션은 되돌릴 수 없다(커밋 후 `abortTransaction()` 확인)
- Q2. 순서를 뒤집으면 반대 방향 불일치. **어떤 순서로도 두 커밋 사이의 창은 남는다** — `ChainedTransactionManager`는 창을 좁힐 뿐 없애지 못한다
- Q3. Outbox 방향 — 쓰기를 외부 저장소 한 곳으로 단일화하면 원자성이 그 저장소의 트랜잭션 하나로 환원된다. 릴레이는 at-least-once이므로 중복 발행이 나고, 소비자 멱등 처리로 최종 1건이 된다

> 외부 시스템은 실제 DB가 아니라 테스트 클래스 안의 in-memory 스텁이다. compose의 Postgres는 `profiles: ["notification"]` 뒤에 있고, 이 명제를 보이는 데 진짜 DB는 필요 없다 — 필요한 건 "Kafka 커밋과 외부 커밋이 별개의 두 연산"이라는 사실뿐이다.

**Lab07 — EOS의 비용** (`Lab07TransactionCostTest`)
- Q1. 일반 Producer vs 트랜잭션 Producer 처리량 비교. 끝 오프셋으로 마커 유무 확인(0개 vs 1개)
- Q2. 300건을 1건씩 300트랜잭션 → 마커 300개(HW 600) / 100건 묶음 3트랜잭션 → 마커 3개(HW 303). "느린 이유"가 추측이 아니라 로그에 남은 control batch 수로 설명된다
- Q3. 트랜잭션이 3개 파티션에 걸치면 **파티션마다** 마커가 하나씩 생긴다

> 속도는 **출력만** 하고 단정하지 않는다. 로컬 Docker에서 성능 부등식은 불안정하다. `assertThat`으로 단정하는 것은 마커 개수·끝 오프셋 같은 결정론적 값뿐이다.

**Lab08 — 장애 내성** (`Lab08FaultToleranceTest`)
- Q1. RF=3 토픽에 `min.insync.replicas=4` → 구조적으로 만족 불가. 트랜잭션 send가 거부되고 **부분 반영은 없다**. 브로커를 죽이지 않고 재현한다
- Q2. kafka-3 정지 → 브로커 2대 상태에서도 트랜잭션이 계속 커밋된다. RF=3 / min ISR=2는 "1대 장애를 견딘다"는 뜻
- Q3. `__transaction_state`가 RF=3이라 브로커 1대가 빠져도 트랜잭션 조회·신규 개시가 된다. 코디네이터를 특정해 죽이는 것은 파티션 배치에 따라 불안정해 시도하지 않는다

> **안전장치**: `docker` CLI가 없거나 브로커가 3대가 아니면 전체 skip. 정지 대상은 kafka-3 하나로 고정(9092 부트스트랩인 kafka-1은 건드리지 않는다). 각 테스트는 `try/finally`로 감싸고 단정문을 `finally` 바깥에 두어, 단정 실패로도 복구를 건너뛰지 않는다. `@AfterAll`에서 브로커 3대 복구를 한 번 더 확인하고, 실패 시 수동 복구 명령을 출력한다. `stop`/`start`만 쓰며 `rm`이나 볼륨 삭제는 하지 않는다.

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
