# kafka-notification 3단계 아키텍처

## 1. 문서 목적

이 문서는 3단계 기준의 최종 아키텍처를 설명한다. 핵심은 "Producer Outbox 보강", "결과 상태 멱등성 강화", "전송 멱등 저장소 분리"가 각각 어느 위치에서 동작하는지 보여주는 것이다.

## 2. 시스템 구성

- Producer App
  - 요청/조회/결과 반영 담당
- Producer PostgreSQL
  - `notification_requests`, `outbox_events`
- `OutboxRelay`
  - Kafka TX 기반 릴레이
- Kafka
  - `read_committed` 기준으로 결과 이벤트 노출
- Worker App
  - 요청 소비, retry/backoff, 결과 이벤트 발행
- Worker PostgreSQL
  - `processed_notifications`
- `SentNotificationStore`
  - 전송 멱등 저장소 추상화

## 3. 아키텍처 해설

### 3.1 보강된 지점

- Producer는 Kafka TX와 `read_committed` 조합으로 Outbox 릴레이와 결과 소비 경계를 강화했다.
- Query 상태 갱신은 최종 상태 가드로 중복 반영을 줄였다.
- Worker sender는 저장소 추상화 덕분에 JVM 내부 캐시 구현에 고정되지 않는다.

### 3.2 남아 있는 failure window

- Kafka TX 커밋 후 JPA 커밋 실패 시 Outbox가 미발행으로 남을 수 있다.
- 이 구간은 Worker의 `processed_notifications` 기반 멱등 소비가 최종 방어선 역할을 한다.
- 전송 멱등 저장소는 DB 구현으로 확장됐지만 분산 환경에서는 Redis 같은 외부 저장소가 더 적합할 수 있다.

## 4. Draw.io XML 소스

- [phase-3-architecture.drawio](./phase-3-architecture.drawio)

## 5. 리뷰 체크리스트

- OutboxRelay, QueryService, WorkerConfig의 보강 포인트가 diagram과 문서에서 일치하는가
- 최종 방어선이 `processed_notifications`라는 점이 과장 없이 표현되는가
- phase-2 대비 어떤 공백이 줄었는지 리뷰어가 바로 읽히는가
