# 03. Producer 기초

Producer는 Kafka로 메시지(레코드)를 보내는 쪽이다.

## 레코드 기본 구조
- topic
- key (선택)
- value (보통 payload)
- headers (선택)

## 중요한 설정/개념

### 1) acks: “브로커가 저장했다고 언제 인정할까?”
- `acks=0`: 보내고 끝(가장 빠르지만 손실 가능)
- `acks=1`: leader에 기록되면 성공
- `acks=all`(또는 `-1`): ISR(in-sync replicas)까지 반영되면 성공(가장 안전, 느릴 수 있음)

로컬 단일 브로커 환경(replica=1)에서는 `acks=all`이어도 체감 차이가 작다.

### 2) retries / idempotence: “재시도하면 중복이 생기지 않나?”
네트워크/브로커 문제로 실패가 나면 재시도하게 되는데, 이때 **중복 전송**이 발생할 수 있다.

- 기본 재시도: “최소 1번(at-least-once)”에 가까워진다(중복 가능).
- **idempotent producer**(멱등 프로듀서)를 켜면, 브로커가 중복을 제거해 “중복 없는 재시도”에 가까워진다.

> 정확히 한 번(Exactly-once)은 “프로듀서 멱등 + 트랜잭션 + 컨슈머 쪽 처리/커밋”까지 함께 설계해야 한다.

### 3) 배치/압축
Producer는 성능을 위해 여러 레코드를 모아(batch) 보내기도 한다.
- 장점: 처리량 증가
- 단점: 지연(latency) 증가 가능

## 이 프로젝트에서 Producer는 어디인가?
- HTTP 요청을 받아서 Kafka로 보내는 부분: `src/main/java/com/study/kafka/web/MessageController.java`
  - `KafkaTemplate<String, String>` 사용
  - key가 없으면: `kafkaTemplate.send(topic, request.message())`
  - key가 있으면: `kafkaTemplate.send(topic, request.key(), request.message())`

## 연습 아이디어
- key를 추가해서 “특정 사용자 ID는 같은 파티션으로” 보내보기
- 실패/재시도 상황을 가정하고 “중복이 생겨도 안전한 처리”를 고민해보기(컨슈머 쪽)
