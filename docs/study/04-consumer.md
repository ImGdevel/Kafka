# 04. Consumer 기초

Consumer는 Kafka에서 메시지를 읽어서 처리하는 쪽이다.

## Consumer Group과 병렬 처리
- 토픽 파티션 수 ≥ 컨슈머 수일 때 병렬 처리가 잘 된다.
- 같은 그룹에서는 한 파티션을 동시에 두 컨슈머가 읽지 않는다.

## 오프셋 커밋이 중요한 이유
“언제 오프셋을 커밋하느냐”가 곧 처리 보장(중복/유실)과 연결된다.

### 대표적인 전달 보장(Delivery Semantics)
- **At-most-once**: 커밋을 먼저 하고 처리하면 → 처리 전에 죽으면 유실 가능
- **At-least-once**: 처리 후 커밋하면 → 커밋 전에 죽으면 재처리되어 중복 가능
- **Exactly-once**: 가능한 범위가 제한적이고(특히 외부 DB 연동), 트랜잭션 설계가 필요

실무에서 가장 흔한 기본은 **at-least-once + 멱등 처리**(중복을 받아들이되 안전하게 처리)다.

## 리밸런싱(Rebalancing)
그룹에 컨슈머가 추가/제거되거나 파티션 수가 바뀌면 파티션 배정이 다시 이뤄진다.
- 처리 중인 메시지가 있으면 “중간에 끊긴 것처럼” 보일 수 있다.
- 커밋 타이밍/처리 설계가 중요해진다.

## 이 프로젝트에서 Consumer는 어디인가?
- 메시지를 받아 로그로 찍는 부분: `src/main/java/com/study/kafka/messaging/MessageListener.java`
  - `@KafkaListener(topics = "${app.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")`

## 연습 아이디어
- 컨슈머에서 일부러 예외를 던져보고(처리 실패), 재처리가 어떻게 되는지 관찰하기
- `auto-offset-reset`을 `earliest`/`latest`로 바꿔서 차이를 체감해보기

