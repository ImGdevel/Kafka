# notification-domain

[상위 설계 문서](../../docs/spec/tech/kafka-notification/README.md)

## 1. 배경과 목표

`notification-domain`은 알림 실습에서 가장 안쪽에 있는 순수 도메인 모듈이다.

이 모듈의 목적은 Kafka, Spring, HTTP 같은 기술 세부사항 없이 알림 전송에 필요한 공통 언어를 고정하는 것이다.

구현 범위:

- `NotificationChannel`
- `NotificationTarget`
- `NotificationContent`
- `NotificationTemplate`
- `NotificationSendResult`
- `NotificationSender`

## 2. 모듈 구조

패키지:

```text
com.study.notification.domain
```

주요 타입 책임:

- `NotificationChannel`
  - v1 채널 범위 `EMAIL`, `SLACK`
- `NotificationTarget`
  - 수신 대상 식별자
- `NotificationContent`
  - 제목과 본문
- `NotificationTemplate`
  - 템플릿 코드
- `NotificationSendResult`
  - 전송 결과와 세부 메시지
- `NotificationSender`
  - 채널별 구현이 따라야 하는 계약

의존 경계:

- 허용: Java 표준 라이브러리
- 금지: Spring, Kafka, Web, `notification-contract`

## 3. 런타임 흐름

이 모듈은 직접 실행되지 않는다.

사용 흐름:

1. Producer 앱이 외부 요청을 해석할 때 채널 타입을 사용한다.
2. Worker 앱이 이벤트를 실제 전송 모델로 바꿀 때 `NotificationTarget`, `NotificationContent`, `NotificationTemplate`을 사용한다.
3. Provider 구현은 `NotificationSender`를 구현하고 `NotificationSendResult`를 반환한다.

## 4. 설정 계약

이 모듈은 설정 파일을 직접 가지지 않는다.

설정 책임은 없다. 다만 아래 정책은 상위 모듈에서 전제로 사용한다.

- 채널은 `EMAIL`, `SLACK`
- 템플릿 코드는 문자열로 전달
- 전송 결과는 성공 여부와 설명을 함께 보관

## 5. 확장 및 마이그레이션 전략

- 새 채널이 필요하면 `NotificationChannel`을 먼저 확장한다.
- 기술 세부 정보가 필요한 타입은 이 모듈에 넣지 않는다.
- Kafka 헤더나 토픽 이름은 `notification-contract`에 둔다.
- 외부 Provider 구현은 이 모듈이 아니라 별도 구현 모듈에 둔다.

## 6. 리뷰 체크리스트

- 도메인 타입에 Kafka payload 개념이 섞이지 않았는가
- 구현체 세부사항이 `NotificationSender` 인터페이스에 새지 않는가
- v1 범위 밖 채널이 무분별하게 추가되지 않았는가
