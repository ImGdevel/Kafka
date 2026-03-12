# kafka-notification 1단계 아키텍처

## 1. 문서 목적

이 문서는 1단계 기준의 배포/컴포넌트 경계와 메시지 이동 방향을 설명한다. 구현 범위는 "단순한 Kafka 알림 구축"이며, 영속 저장소나 고급 EOS 보장은 아직 포함하지 않는다.

## 2. 시스템 구성

- Client
  - Producer App의 HTTP API를 호출한다.
- `notification-producer-app`
  - 요청을 이벤트로 변환해 Kafka에 발행한다.
  - 결과 이벤트를 받아 조회 상태를 갱신한다.
- Kafka
  - 요청, 성공, 실패, DLT 토픽을 제공한다.
- `notification-worker-app`
  - 요청 이벤트를 소비하고 sender를 선택해 전송한다.
- `notification-provider-sandbox`
  - Email/Slack 채널의 스텁 전송을 수행한다.

## 3. 아키텍처 해설

### 3.1 핵심 경계

- HTTP 경계는 Producer에서만 노출한다.
- Kafka 경계는 Producer와 Worker 사이의 비동기 분리선이다.
- 실제 외부 Provider 대신 sandbox sender를 사용해 채널 분기와 실패 경로만 검증한다.

### 3.2 현재 제약

- 상태 저장이 메모리 기반이라 Producer 재시작 시 조회 상태가 초기화된다.
- Worker 중복 소비에 대한 영속 멱등성은 아직 없다.
- DLT 토픽은 생성되지만 소비/운영 절차는 다음 단계 대상이다.

## 4. Draw.io XML 소스

- [phase-1-architecture.drawio](./phase-1-architecture.drawio)

## 5. 리뷰 체크리스트

- Client -> Producer -> Kafka -> Worker -> Result 토픽 흐름이 구현과 일치하는가
- 샌드박스 sender가 외부 Provider 자리를 대체한다는 점이 명확한가
- 2단계에서 추가될 영속화/멱등성 지점이 분리되어 보이는가
