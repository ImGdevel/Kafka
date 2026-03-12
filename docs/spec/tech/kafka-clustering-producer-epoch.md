# 프로듀서 Transaction Epoch 충돌 문제

## 1. 언제 발생하는가

`transaction-id-prefix`를 설정한 트랜잭셔널 프로듀서에서 아래 상황이 발생할 때.

| 트리거 | 설명 |
|---|---|
| 동일 `transactional.id`로 새 인스턴스가 기동 | 브로커가 기존 프로듀서를 강제 종료(fencing)하고 새 인스턴스에 epoch 부여 |
| 앱 재시작 후 이전 트랜잭션이 미완료 상태 | 브로커에 열린 트랜잭션이 남아있어 충돌 |
| 동일 prefix로 여러 인스턴스 동시 실행 | 두 인스턴스가 같은 `transactional.id` 경쟁 → 둘 다 실패 가능 |

### Transactional ID와 Epoch

```
transactional.id = transaction-id-prefix + (Spring이 자동으로 suffix 추가)
예: notification-outbox-tx- → notification-outbox-tx-0, notification-outbox-tx-1, ...

브로커는 transactional.id별로 epoch(단조 증가 숫자)를 관리한다.
새 프로듀서가 초기화되면 epoch가 +1 증가한다.
이전 epoch의 프로듀서가 메시지를 보내면 → InvalidProducerEpochException
```

---

## 2. 어떤 문제가 생기는가

### 시나리오 A: 앱 재시작 중 진행 중인 트랜잭션 있을 때

```
앱 인스턴스 A: notification-outbox-tx-0, epoch=5로 트랜잭션 시작
앱 재시작 →
새 인스턴스 A': notification-outbox-tx-0, epoch=6으로 초기화
  → 브로커가 epoch=5 트랜잭션을 강제 abort
  → 기존 트랜잭션이 깨끗하게 정리됨 (이건 의도된 동작)

문제 없음 (단일 인스턴스 재시작은 정상 동작)
```

### 시나리오 B: 동일 transactional.id로 2개 인스턴스 동시 실행

```
인스턴스 A: notification-outbox-tx-0, epoch=5 (실행 중)
인스턴스 B: notification-outbox-tx-0, epoch=6 으로 초기화 (B 기동)
  → 브로커가 A의 epoch=5를 무효화
  → A가 다음 send 시도 시: InvalidProducerEpochException
  → A의 트랜잭션 abort → OutboxRelay 실패 → 알림 발행 안됨
```

### 시나리오 C: max.in.flight.requests.per.connection 오설정

```yaml
# 현재 설정
max.in.flight.requests.per.connection: 5
enable.idempotence: true
```

`enable.idempotence=true`이면 `max.in.flight.requests.per.connection`은 최대 5까지만 허용.
5 초과로 설정하면 `ConfigException` 발생으로 앱 기동 실패.

---

## 3. 어떤 문제가 생기는가 (실제 증상)

- `InvalidProducerEpochException`: 발행 실패, 트랜잭션 abort
- `ProducerFencedException`: 더 높은 epoch의 프로듀서에 의해 차단됨
- OutboxRelay/NotificationWorkerService 에서 예외 → 메시지 발행 중단
- 단일 인스턴스 재시작은 **정상 동작** — 이전 epoch abort 후 새 epoch로 재개

---

## 4. 어떻게 해결하는가

### 단일 인스턴스 (현재 구성) — 추가 조치 불필요

앱이 재시작되면 브로커가 이전 epoch를 무효화하고 새 epoch를 부여한다. 이는 Kafka의 정상적인 Fencing 메커니즘이다. 재시작 중 진행 중이던 트랜잭션은 abort되고, Outbox는 미발행 상태로 남아 다음 릴레이 주기에 재처리된다(Pillar 3 멱등 게이트로 중복 방지).

### 다중 인스턴스 시 — prefix에 인스턴스 ID 포함

```yaml
# 각 인스턴스마다 고유한 transactional.id를 갖도록 설정
# Spring이 자동으로 suffix를 붙이지만, prefix가 같으면 충돌 가능
transaction-id-prefix: ${APP_TRANSACTION_ID_PREFIX:notification-outbox-tx-}${INSTANCE_ID:0}-
```

또는 환경변수로 주입:
```bash
# 인스턴스 1
APP_TRANSACTION_ID_PREFIX=notification-outbox-tx-inst1-

# 인스턴스 2
APP_TRANSACTION_ID_PREFIX=notification-outbox-tx-inst2-
```

각 인스턴스가 고유한 `transactional.id`를 가지므로 epoch 충돌이 발생하지 않는다.

### idempotence + in-flight 조합 제약

```
enable.idempotence=true 조건:
  - acks=all 필수
  - retries > 0 필수
  - max.in.flight.requests.per.connection ≤ 5 필수

이 조건을 지키면 브로커가 시퀀스 번호로 중복 배치를 탐지하여
네트워크 재전송으로 인한 중복 발행을 차단한다.
```

---

## 5. 현재 코드 현황

**파일:** [notification-producer-app/application.yaml](../../../kafka-notification/notification-producer-app/src/main/resources/application.yaml)

```yaml
producer:
  transaction-id-prefix: ${APP_NOTIFICATION_OUTBOX_TX_ID_PREFIX:notification-outbox-tx-}
  acks: all
  retries: 3
  properties:
    enable.idempotence: true
    max.in.flight.requests.per.connection: 5  # idempotence 최대 허용값
```

**파일:** [notification-worker-app/application.yaml](../../../kafka-notification/notification-worker-app/src/main/resources/application.yaml)

```yaml
producer:
  transaction-id-prefix: notification-worker-tx-
  acks: all
  retries: 3
  properties:
    enable.idempotence: true
```

현재는 단일 인스턴스 구성이므로 **epoch 충돌이 발생하지 않는다.**
다중 인스턴스로 확장 시 `transaction-id-prefix`에 인스턴스 식별자를 포함해야 한다.

---

## 6. 검증

```bash
# 동일 트랜잭션 ID 충돌 시뮬레이션 (학습용):
# 1. notification-producer-app 기동 (epoch=1)
# 2. 동일 transaction-id-prefix로 두 번째 인스턴스 기동 (epoch=2)
# 3. 첫 번째 인스턴스의 OutboxRelay에서 InvalidProducerEpochException 확인

# 로그에서 Epoch 확인
grep -i "epoch\|fenced\|InvalidProducer" <app-log>
```
