# kafka-notification 1단계 설계

## 1. 배경과 목표

1단계의 목적은 Kafka를 이용해 "요청 접수"와 "실제 전송"을 분리한 가장 단순한 알림 실습 흐름을 세우는 것이다.

이번 단계에서는 다음 목표를 달성한다.

- Producer App과 Worker App을 분리해 역할 경계를 명확히 둔다.
- `notification.requested`, `notification.sent`, `notification.failed` 토픽을 중심으로 기본 이벤트 흐름을 만든다.
- Email/Slack 샌드박스 전송기로 채널 분기를 검증한다.
- 조회 API와 테스트 코드를 포함해 로컬에서 전체 흐름을 재현할 수 있게 한다.

## 2. 모듈 구조

### 2.1 모듈 책임

- `notification-contract`
  - 요청/성공/실패 이벤트 DTO와 토픽 상수 제공
- `notification-domain`
  - 채널, 대상, 본문, 템플릿, 전송 결과와 Sender 계약 정의
- `notification-provider-sandbox`
  - 샌드박스 Email/Slack sender 제공
- `notification-producer-app`
  - HTTP 요청 수신, 상태 조회, 결과 이벤트 반영
- `notification-worker-app`
  - 요청 이벤트 소비, 재시도, 성공/실패 이벤트 발행

### 2.2 현재 경계

- Producer는 요청 접수와 조회 상태 반영에 집중한다.
- Worker는 채널별 전송과 실패 처리에 집중한다.
- 상태 저장은 아직 in-memory projection 수준이다.
- 브로커 DLT 소비와 영속 저장소는 다음 단계에서 도입한다.

## 3. 런타임 흐름

### 3.1 요청 성공 흐름

1. Client가 `POST /api/notifications`로 알림 요청을 보낸다.
2. Producer가 `NotificationRequestedEvent`를 만들어 `notification.requested`에 발행한다.
3. Worker가 이벤트를 소비하고 채널에 맞는 sandbox sender를 선택한다.
4. 전송 성공 시 `notification.sent`를 발행한다.
5. Producer가 결과 이벤트를 소비해 조회 상태를 `SENT`로 갱신한다.

### 3.2 요청 실패 흐름

1. Worker가 전송에 실패하면 1회 재시도한다.
2. 재시도까지 실패하면 `notification.failed`를 발행한다.
3. Producer가 실패 결과를 반영해 조회 상태를 `FAILED`로 갱신한다.

## 4. 설정 계약

### 4.1 Producer

- HTTP 포트: `8082`
- Kafka bootstrap: `localhost:9092`
- 허용 채널: `EMAIL`, `SLACK`
- 조회 API: `GET /api/notifications/{notificationId}`

### 4.2 Worker

- Consumer group: `notification-worker-group`
- 최대 재시도: `2`
- 기본 backoff: `1000ms`

### 4.3 토픽

- `notification.requested`
- `notification.sent`
- `notification.failed`
- `notification.requested.dlt`

## 5. 확장 및 마이그레이션 전략

- Producer 요청/상태를 DB로 영속화한다.
- Outbox 패턴으로 요청 저장과 Kafka 발행을 분리한다.
- Worker에 MANUAL ack와 멱등 소비 저장소를 추가한다.
- 샌드박스 sender의 전송 멱등성과 재시작 후 복구 전략을 보강한다.

## 6. 리뷰 체크리스트

- Producer와 Worker의 책임이 문서와 코드에서 같은가
- 토픽 이름과 이벤트 DTO가 문서와 코드에서 같은가
- 상태 조회가 in-memory projection이라는 제한이 명시되어 있는가
- 실패 재시도 횟수와 강제 실패 규칙이 테스트와 일치하는가
