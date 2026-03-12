# DLT 파티션 라우팅 문제

## 1. 언제 발생하는가

`DeadLetterPublishingRecoverer`에서 DLT 파티션을 **원본 레코드의 파티션 번호로 고정**할 때, 원본 토픽과 DLT 토픽의 파티션 수가 다르면 발생한다.

```java
// 문제가 있는 기존 코드
(record, ex) -> new TopicPartition(dltTopic, record.partition())
```

**구체적 트리거 조건:**

| 시나리오 | 설명 |
|---|---|
| 원본 토픽 파티션 수 > DLT 토픽 파티션 수 | 원본 파티션 2 → DLT 파티션 2가 없음 → 즉시 발생 |
| 클러스터 확장 시 파티션 수 변경 | 원본만 파티션 늘리고 DLT는 그대로 → 새 파티션 메시지 DLT 라우팅 실패 |
| DLT 토픽을 별도 설정(파티션 수 1)으로 생성 | 원본이 파티션 3개이면 파티션 1, 2 메시지는 무조건 실패 |

---

## 2. 어떤 문제가 생기는가

```
흐름:
  컨슈머 메시지 처리 실패
  → DefaultErrorHandler: 1회 재시도
  → 재시도도 실패 → DeadLetterPublishingRecoverer 호출
  → new TopicPartition(dltTopic, record.partition()) 에서 파티션이 없는 번호 지정
  → InvalidPartitionException 발생
  → Recoverer 자체가 실패 → DefaultErrorHandler가 다시 재시도 시도
  → 재시도 횟수 소진 → 예외 전파 → 컨슈머 스레드 중단 가능
```

**증상:**

- DLT 메시지가 전송되지 않음 → 실패 메시지 추적 불가
- `InvalidPartitionException` 로그가 반복적으로 출력됨
- 최악의 경우 컨슈머 스레드가 중단되어 해당 파티션 소비 전체 멈춤

---

## 3. 어떻게 해결하는가

### 해결 전략 A: 파티션 -1 (key 기반 파티셔닝 위임) — 현재 적용

```java
// 파티션 -1 → 브로커가 메시지 key를 기준으로 파티션 배정
(record, ex) -> new TopicPartition(dltTopic, -1)
```

- key가 있으면 `murmur2(key) % DLT_PARTITION_COUNT`로 결정
- key가 null이면 라운드로빈
- DLT 토픽 파티션 수와 무관하게 항상 유효한 파티션에 배정됨

**적합한 경우:** DLT도 여러 파티션을 두고 병렬 처리가 필요할 때

### 해결 전략 B: 파티션 0 고정 (단일 파티션 DLT)

```java
(record, ex) -> new TopicPartition(dltTopic, 0)
```

- DLT 토픽 파티션 수를 1로 유지하고 항상 0번에 라우팅
- 파티션 수 변경에 완전히 독립적
- 단, DLT 처리 처리량이 파티션 1개로 제한됨

**적합한 경우:** DLT 메시지 양이 적고 순서를 유지해야 할 때

---

## 4. 현재 코드 적용 결과

**파일:** [KafkaErrorHandlerConfig.java](../../../infra-message-queue/src/main/java/com/study/kafka/config/KafkaErrorHandlerConfig.java)

```java
// 변경 전
(record, ex) -> new TopicPartition(dltTopic, record.partition())

// 변경 후: 파티션 -1 → key 기반 라우팅
(record, ex) -> new TopicPartition(dltTopic, -1)
```

`study-topic`(파티션 3)과 `study-topic-dlt`(파티션 3)는 현재 동일하므로 `record.partition()`도 동작하지만,
파티션 수를 변경하는 순간 즉시 깨지는 코드였다. `-1`로 변경하여 파티션 수 변경에 독립적으로 만든다.

---

## 5. 검증

```bash
# 파티션 수가 다른 DLT 토픽으로 실험
# 1. study-topic-dlt를 파티션 1개로 재생성 (볼륨 삭제 후 재기동 필요)
# 2. "fail"이 포함된 메시지 전송
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"key":"test","value":"fail message"}'

# 3. DLT 토픽에 메시지 도착 확인
docker exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic study-topic-dlt \
  --from-beginning
```
