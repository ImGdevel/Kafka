# kafka-notification 2단계 아키텍처

## 1. 문서 목적

이 문서는 2단계에서 추가된 Producer/Worker 영속화 구조와 1차 EOS 구성요소를 설명한다. 핵심은 "요청 저장", "Outbox 릴레이", "멱등 소비"가 각각 어느 계층에 배치되는지 명확히 하는 것이다.

## 2. 시스템 구성

- Client
  - Producer App의 HTTP API 호출
- `notification-producer-app`
  - 요청 저장, 조회 API, 결과 상태 반영
- Producer PostgreSQL
  - `notification_requests`, `outbox_events`
- `OutboxRelay`
  - 미발행 outbox 레코드를 Kafka로 릴레이
- Kafka
  - 요청/결과 이벤트 중계
- `notification-worker-app`
  - 요청 소비, retry/backoff, 결과 이벤트 발행
- Worker PostgreSQL
  - `processed_notifications`

## 3. 아키텍처 해설

### 3.1 1단계 대비 달라진 점

- Producer 조회 상태가 메모리에서 DB로 이동했다.
- Kafka 발행이 HTTP 요청 처리 경로에서 분리되고 `OutboxRelay`로 이관됐다.
- Worker는 처리 이력을 DB에 남겨 중복 redelivery를 차단한다.

### 3.2 아직 남아 있는 공백

- `OutboxRelay`는 아직 `send().get()`과 `markPublished()` 사이에 원자성 공백이 있다.
- 샌드박스 전송 멱등성은 notificationId 전달 수준까지 도입됐지만 저장소 추상화는 아직 없다.
- Producer 결과 상태 갱신은 재전달 시 중복 UPDATE가 발생할 수 있다.

## 4. Draw.io XML 소스

- [phase-2-architecture.drawio](./phase-2-architecture.drawio)

## 5. 리뷰 체크리스트

- Producer DB와 Worker DB의 책임이 혼합되지 않는가
- OutboxRelay가 HTTP 요청 스레드와 분리된 보조 경로라는 점이 명확한가
- Worker의 멱등 소비와 결과 이벤트 발행 순서가 문서와 코드에서 일치하는가
