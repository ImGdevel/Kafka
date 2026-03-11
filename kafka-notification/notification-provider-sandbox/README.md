# notification-provider-sandbox

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 1. 배경과 목표

`notification-provider-sandbox`는 Worker 앱이 사용할 실습용 채널 구현 모듈이다.

실제 Email, Slack API를 연동하지 않고도 성공과 실패 흐름을 재현할 수 있도록 가짜 Sender를 제공한다.

## 2. 모듈 구조

패키지:

```text
com.study.notification.provider.sandbox
```

포함 구현:

- `SandboxEmailSender`
- `SandboxSlackSender`

공통 규칙:

- `NotificationSender` 구현
- 정상 템플릿은 성공 반환
- `templateCode=FAIL_ALWAYS`면 `IllegalStateException` 발생

의존 경계:

- 허용: `notification-domain`
- 금지: Kafka 토픽/헤더, REST API, 외부 비밀키

## 3. 런타임 흐름

1. Worker가 채널에 맞는 Sender bean을 선택한다.
2. Sender가 `NotificationTarget`, `NotificationContent`, `NotificationTemplate`을 받는다.
3. 실패 강제 규칙이 아니면 `NotificationSendResult`를 반환한다.
4. 강제 실패 규칙이면 예외를 던져 Worker 재시도 흐름으로 넘긴다.

## 4. 설정 계약

현재 설정 키는 없다.

현재 구현된 실패 규칙:

- `templateCode=FAIL_ALWAYS`

후속 확장 후보:

- 채널별 지연 시간
- 확률 기반 실패율
- 테스트용 세부 응답 코드

## 5. 확장 및 마이그레이션 전략

- 실 Provider 연동이 필요해지면 별도 구현 모듈로 분리한다.
- 실패 주입 정책이 많아지면 설정 기반 전략 클래스로 이동한다.
- Sandbox 규칙은 도메인 모듈이나 contract 모듈로 올리지 않는다.

## 6. 리뷰 체크리스트

- 외부 API 키나 실 연동 코드가 들어가 있지 않은가
- Worker 전용 모듈이라는 경계가 유지되는가
- 실패 주입 규칙이 도메인에 새지 않는가
