# notification-worker-app

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 1. 배경과 목표

`notification-worker-app`은 Kafka의 알림 요청 이벤트를 소비해 실제 채널 전송을 수행하는 Spring Boot 앱이다.

이번 단계의 구현은 Worker 내부에서 1회 재시도 후 성공/실패 이벤트를 발행하는 데 초점을 둔다.

## 2. 모듈 구조

패키지:

```text
com.study.notification.worker
├── config
│   └── NotificationWorkerConfig
├── messaging
│   └── NotificationRequestedListener
└── service
    └── NotificationWorkerService
```

주요 책임:

- `NotificationRequestedListener`
  - `notification.requested` 소비
- `NotificationWorkerService`
  - Sender 선택
  - 재시도
  - `notification.sent`, `notification.failed` 발행
- `NotificationWorkerConfig`
  - `SandboxEmailSender`, `SandboxSlackSender` bean 등록

## 3. 런타임 흐름

1. `NotificationRequestedListener`가 `notification.requested`를 소비한다.
2. `NotificationWorkerService`가 `channel`로 Sender를 선택한다.
3. `NotificationTarget`, `NotificationContent`, `NotificationTemplate`로 변환한다.
4. 최대 2회까지 전송을 시도한다.
5. 성공 시 `notification.sent`에 `NotificationSentEvent`를 발행한다.
6. 실패 시 `notification.failed`에 `NotificationFailedEvent`를 발행한다.

채널 선택 규칙:

- `EMAIL` -> `SandboxEmailSender`
- `SLACK` -> `SandboxSlackSender`

실패 강제 규칙:

- `templateCode=FAIL_ALWAYS`

## 4. 설정 계약

주요 설정:

```yaml
spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: notification-worker-group

app:
  notification:
    retry:
      max-attempts: ${APP_NOTIFICATION_RETRY_MAX_ATTEMPTS:2}
      backoff-millis: ${APP_NOTIFICATION_RETRY_BACKOFF_MILLIS:1000}
```

운영 규칙:

- HTTP 엔드포인트를 노출하지 않는다.
- 실패 시 broker DLT 대신 `notification.failed`를 먼저 사용한다.
- `notification.requested.dlt`는 후속 단계용 토픽이다.

## 5. 확장 및 마이그레이션 전략

- 재시도 정책이 복잡해지면 `DefaultErrorHandler` 기반 전략으로 이동한다.
- 실패 재처리 앱이 필요하면 별도 관리자 앱으로 분리한다.
- 실 Provider 모듈이 추가되면 `NotificationWorkerConfig`에서 교체 가능하도록 유지한다.

## 6. 리뷰 체크리스트

- Worker가 HTTP 엔드포인트를 노출하지 않는가
- 채널 선택 규칙이 domain이나 contract에 섞이지 않는가
- 성공/실패 이벤트 발행 기준이 문서와 코드에서 일치하는가
- DLT와 실패 이벤트의 역할이 혼동되지 않는가
