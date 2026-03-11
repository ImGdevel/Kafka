# notification-contract

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 1. 배경과 목표

`notification-contract`는 Producer 앱과 Worker 앱이 Kafka로 주고받는 계약을 정의한다.

이 모듈은 통신 포맷과 토픽/헤더 이름을 고정해 두 앱이 같은 이벤트를 같은 의미로 해석하도록 만든다.

## 2. 모듈 구조

패키지:

```text
com.study.notification.contract
```

주요 타입:

- `NotificationRequestedEvent`
  - `notificationId`, `traceId`, `channel`, `recipient`, `subject`, `body`, `templateCode`
- `NotificationSentEvent`
  - `notificationId`, `traceId`, `channel`, `provider`
- `NotificationFailedEvent`
  - `notificationId`, `traceId`, `channel`, `reason`
- `NotificationTopics`
  - `notification.requested`
  - `notification.sent`
  - `notification.failed`
  - `notification.requested.dlt`
- `NotificationHeaderNames`
  - `trace-id`
  - `notification-id`
  - `channel`

의존 경계:

- 허용: `notification-domain`
- 금지: Spring Kafka 설정, Sender 구현, REST 요청/응답 타입

## 3. 런타임 흐름

1. Producer 앱이 `NotificationRequestedEvent`를 만든다.
2. Kafka에 `NotificationTopics.REQUESTED`로 발행한다.
3. Worker 앱이 요청 이벤트를 소비한다.
4. 성공 시 `NotificationSentEvent`, 실패 시 `NotificationFailedEvent`를 발행한다.
5. Producer 앱은 결과 이벤트를 다시 소비해 조회 projection을 갱신할 수 있다.

현재 구현 상태:

- JSON 직렬화 대상 DTO 구현 완료
- 토픽/헤더 상수 구현 완료
- 버전 필드나 schema registry 연동은 아직 없음

## 4. 설정 계약

이 모듈은 설정 파일을 직접 갖지 않는다.

상위 모듈은 아래 이름을 그대로 사용해야 한다.

- 요청 토픽: `notification.requested`
- 성공 토픽: `notification.sent`
- 실패 토픽: `notification.failed`
- DLT 토픽: `notification.requested.dlt`
- 헤더: `trace-id`, `notification-id`, `channel`

## 5. 확장 및 마이그레이션 전략

- 필드 추가는 역직렬화 호환성을 먼저 확인한다.
- 토픽 이름 변경은 운영 흐름 전체에 영향을 주므로 가급적 피한다.
- payload가 커지면 DTO와 이벤트 버전 전략을 별도로 분리한다.
- Broker DLT나 관리자 앱이 생겨도 계약 상수는 이 모듈을 단일 기준으로 유지한다.

## 6. 리뷰 체크리스트

- 이벤트 DTO에 비즈니스 정책이 들어가 있지 않은가
- 토픽/헤더 이름이 앱 문서와 코드에서 일치하는가
- 성공 이벤트와 실패 이벤트의 책임이 분리되어 있는가
