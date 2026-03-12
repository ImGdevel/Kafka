# kafka-notification 2단계 설계

## 1. 배경과 목표

2단계의 목적은 1단계의 단순한 Kafka 알림 흐름을 "영속화 가능한 구조"로 바꾸고, Worker 쪽에서 EOS의 핵심 골격을 먼저 세우는 것이다.

이번 단계의 목표는 다음과 같다.

- Producer 요청과 조회 상태를 DB에 저장한다.
- Outbox 패턴으로 요청 접수와 Kafka 발행을 분리한다.
- Worker에 MANUAL ack와 `processed_notifications` 기반 멱등 소비를 추가한다.
- 테스트 환경을 EmbeddedKafka + H2 + Flyway 조합으로 올려 영속화 경로를 검증한다.

## 2. 모듈 구조

### 2.1 Producer 쪽 추가 책임

- `NotificationRequestEntity`, `NotificationRequestRepository`
  - 요청/조회 상태 영속화
- `OutboxEventEntity`, `OutboxEventRepository`
  - 미발행 이벤트 저장
- `OutboxRelay`
  - outbox 테이블을 읽어 Kafka로 릴레이

### 2.2 Worker 쪽 추가 책임

- `ProcessedNotificationEntity`, `ProcessedNotificationRepository`
  - 이미 처리한 notificationId 기록
- `NotificationRequestedListener`
  - `Acknowledgment`를 받는 MANUAL ack 진입점
- `NotificationWorkerService`
  - 처리 결과 저장 + 결과 이벤트 발행 + 멱등 소비 게이트

## 3. 런타임 흐름

### 3.1 Producer 영속화 흐름

1. Producer가 요청을 받으면 `notification_requests`에 상태를 저장한다.
2. 같은 트랜잭션에서 `outbox_events`에 미발행 이벤트를 저장한다.
3. `OutboxRelay`가 미발행 이벤트를 읽고 Kafka `notification.requested`에 발행한다.
4. Producer 조회 API는 DB에 저장된 상태를 기준으로 응답한다.

### 3.2 Worker 처리 흐름

1. Worker가 `notification.requested`를 소비한다.
2. `processed_notifications`에 같은 `notificationId`가 있는지 먼저 확인한다.
3. 전송 성공 시 `notification.sent`를 발행하고 처리 결과를 저장한다.
4. 전송 실패 시 retry/backoff 후 `notification.failed`를 발행하고 실패 결과를 저장한다.
5. 처리 완료 뒤에만 MANUAL ack를 호출한다.

## 4. 설정 계약

### 4.1 Producer

- Flyway 마이그레이션: `db/migration/V1__create_notification_tables.sql`
- 영속 테이블
  - `notification_requests`
  - `outbox_events`

### 4.2 Worker

- Flyway 마이그레이션: `db/migration/V1__create_processed_notifications.sql`
- 영속 테이블
  - `processed_notifications`

### 4.3 로컬 인프라

- `docker-compose.notification.yml`에 Producer/Worker용 PostgreSQL을 추가한다.
- 테스트는 EmbeddedKafka + H2를 사용한다.

## 5. 확장 및 마이그레이션 전략

- `OutboxRelay`의 `send + markPublished`를 Kafka TX로 더 강하게 묶는다.
- Producer 결과 상태 갱신을 최종 상태 가드로 보강한다.
- 샌드박스 전송 멱등 저장소를 인터페이스로 분리해 재시작 이후 경계까지 설명 가능하게 만든다.

## 6. 리뷰 체크리스트

- 요청 저장과 outbox 저장이 같은 트랜잭션 경계 안에 있는가
- Worker가 MANUAL ack를 처리 완료 뒤에만 호출하는가
- `processed_notifications`가 중복 소비 차단 경계로 설명되는가
- 테스트 문서가 EmbeddedKafka + H2 기준과 일치하는가
