# kafka-notification 3단계 설계

## 1. 배경과 목표

3단계의 목적은 2단계에서 남겨 둔 원자성/멱등성 공백을 문서와 코드 양쪽에서 정리해 "보강된 EOS 실습 구조"로 마무리하는 것이다.

이번 단계의 목표는 다음과 같다.

- Outbox 릴레이를 Kafka TX로 감싸 `send + markPublished` 경계를 더 강하게 묶는다.
- Producer 상태 갱신을 최종 상태 가드로 보강해 중복 UPDATE를 줄인다.
- 샌드박스 전송 멱등 저장소를 인터페이스로 분리하고 DB 기반 구현을 연결한다.
- 최종 아키텍처 문서에서 남아 있는 한계와 방어선을 함께 설명한다.

## 2. 모듈 구조

### 2.1 추가된 책임

- `SentNotificationStore`
  - 전송 멱등 저장소 계약
- `DbSentNotificationStore`
  - `processed_notifications` 기반 dedup 저장소
- `NotificationWorkerConfig`
  - sender와 저장소 연결
- `NotificationQueryService`
  - `ACCEPTED` 상태일 때만 최종 상태로 갱신

### 2.2 보강된 책임

- `OutboxRelay`
  - Kafka TX 안에서 이벤트 발행과 `published` 마킹 수행
- Producer consumer 설정
  - `read_committed`로 Worker TX 커밋 후 이벤트만 반영

## 3. 런타임 흐름

### 3.1 Producer 보강 흐름

1. `OutboxRelay`가 미발행 레코드를 읽는다.
2. Kafka `executeInTransaction(...)` 안에서 이벤트 발행과 `markPublished()`를 수행한다.
3. Producer의 결과 소비는 `read_committed` 기준으로 Worker TX 커밋 후 이벤트만 읽는다.
4. `NotificationQueryService`는 이미 최종 상태면 갱신을 건너뛴다.

### 3.2 Worker 보강 흐름

1. `DbSentNotificationStore`가 `processed_notifications`를 조회해 중복 전송 가능성을 확인한다.
2. sandbox sender는 내부 맵이 아니라 `SentNotificationStore` 계약에 의존한다.
3. 재시작 후에도 처리 이력과 전송 멱등 판단 기준을 같은 저장소 계열에서 설명할 수 있다.

## 4. 설정 계약

### 4.1 Producer

- `spring.kafka.consumer.properties.isolation.level=read_committed`
- `spring.kafka.producer.transaction-id-prefix=${APP_NOTIFICATION_OUTBOX_TX_ID_PREFIX:notification-outbox-tx-}`
- `spring.kafka.producer.properties.enable.idempotence=true`

### 4.2 Worker

- `NotificationWorkerConfig`에서 `SentNotificationStore`를 DB 구현으로 wiring
- `processed_notifications`가 소비 이력과 전송 멱등 설명의 공통 저장소 역할 수행

## 5. 확장 및 마이그레이션 전략

- 멀티 인스턴스 환경에서 stronger dedup이 필요하면 `SentNotificationStore`의 Redis 구현을 추가한다.
- Outbox 발행 경계를 더 줄이고 싶다면 CDC 기반 릴레이 또는 별도 publish-confirm 전략을 검토한다.
- DLT 운영, 템플릿 렌더링, 실 Provider 연동은 여전히 후속 단계다.

## 6. 리뷰 체크리스트

- `OutboxRelay`의 Kafka TX 설명이 현재 코드와 일치하는가
- Producer 상태 갱신이 `ACCEPTED -> SENT/FAILED` 경계로 설명되는가
- `SentNotificationStore`와 `processed_notifications` 관계가 문서에서 명확한가
- 남은 failure window가 숨겨지지 않고 드러나는가
