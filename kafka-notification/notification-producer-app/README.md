# notification-producer-app

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 1. 배경과 목표

`notification-producer-app`은 외부 알림 요청을 받아 Kafka에 적재하는 Spring Boot 앱이다.

이 앱은 요청을 받아들이고 이벤트를 발행하며, Worker 결과 이벤트를 소비해 조회 상태를 메모리에 유지한다. 실제 전송, 재시도, 실패 판단은 하지 않는다.

## 2. 모듈 구조

패키지:

```text
com.study.notification.producer
├── config
│   ├── NotificationKafkaTopicConfig
│   └── NotificationProducerProperties
├── messaging
│   └── NotificationResultListener
├── service
│   ├── NotificationProducerService
│   ├── NotificationQueryService
│   ├── NotificationQuerySnapshot
│   └── NotificationQueryStatus
└── web
    ├── NotificationAcceptedResponse
    ├── NotificationController
    ├── NotificationRequest
    └── NotificationStatusResponse
```

주요 책임:

- `NotificationController`
  - `POST /api/notifications`
  - `GET /api/notifications/{notificationId}`
- `NotificationRequest`
  - 입력 검증
- `NotificationProducerService`
  - 알림 ID, Trace ID 생성
  - `NotificationRequestedEvent` 발행
  - `ACCEPTED` 상태 projection
  - Kafka 헤더 설정
- `NotificationResultListener`
  - `notification.sent`, `notification.failed` 소비
  - 조회 상태 갱신
- `NotificationQueryService`
  - in-memory 조회 상태 관리
- `NotificationKafkaTopicConfig`
  - 요청/성공/실패/DLT 토픽 생성

## 3. 런타임 흐름

요청 접수 흐름:

1. 클라이언트가 `POST /api/notifications`를 호출한다.
2. 요청 바디를 검증한다.
3. `notificationId`, `traceId`를 새로 만든다.
4. `NotificationRequestedEvent`를 생성한다.
5. `NotificationHeaderNames` 기준으로 헤더를 채운다.
6. 조회 저장소에 `ACCEPTED` 상태를 기록한다.
7. `notification.requested`로 발행한다.
8. `202 Accepted` 응답을 반환한다.

결과 조회 흐름:

1. Worker가 `notification.sent` 또는 `notification.failed`를 발행한다.
2. `NotificationResultListener`가 결과 이벤트를 소비한다.
3. `NotificationQueryService`가 상태를 `SENT` 또는 `FAILED`로 갱신한다.
4. 클라이언트가 `GET /api/notifications/{notificationId}`로 상태를 조회한다.

## 4. 설정 계약

기본 포트:

- `8082`

주요 설정:

```yaml
server:
  port: ${SERVER_PORT:8082}

spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ${SPRING_KAFKA_CONSUMER_GROUP_ID:notification-producer-query-group}
      auto-offset-reset: latest

app:
  notification:
    producer:
      allowed-channels:
        - EMAIL
        - SLACK
```

API 계약:

- Method: `POST`
- Path: `/api/notifications`
- Method: `GET`
- Path: `/api/notifications/{notificationId}`

Request body:

```json
{
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "subject": "welcome",
  "body": "hello",
  "templateCode": "WELCOME"
}
```

접수 응답 예시:

```json
{
  "notificationId": "uuid",
  "traceId": "uuid",
  "status": "ACCEPTED"
}
```

조회 응답 예시:

```json
{
  "notificationId": "uuid",
  "traceId": "uuid",
  "channel": "EMAIL",
  "recipient": "user@example.com",
  "subject": "welcome",
  "templateCode": "WELCOME",
  "status": "SENT",
  "provider": "SandboxEmailSender",
  "reason": null
}
```

## 5. 확장 및 마이그레이션 전략

- 채널별 요청 검증이 복잡해지면 validator를 분리한다.
- 현재 조회 상태는 in-memory projection이라 앱 재기동 시 초기화된다.
- 상태 보존이 필요해지면 별도 query 앱이나 저장소를 붙인다.
- Producer가 전송 책임을 갖지 않도록 경계를 유지한다.

## 6. 리뷰 체크리스트

- Producer가 실제 전송 로직을 들고 있지 않은가
- 응답이 동기 전송 성공을 의미하지 않는가
- 조회 API가 결과 토픽 상태와 일관되게 갱신되는가
- 토픽/헤더 계약이 `notification-contract`와 일치하는가
