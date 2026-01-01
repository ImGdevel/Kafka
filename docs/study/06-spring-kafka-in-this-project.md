# 06. 이 프로젝트의 Spring Kafka 코드 읽기

이 프로젝트는 “HTTP로 메시지 받기 → Kafka로 발행 → 컨슈머가 로그로 출력” 흐름을 최소 코드로 구성했다.

## 1) 설정 파일
`src/main/resources/application.yaml`
- `spring.kafka.bootstrap-servers: localhost:9092`
- `spring.kafka.consumer.group-id: study-group`
- `app.kafka.topic: study-topic`

## 2) 토픽 생성(애플리케이션 시작 시)
`src/main/java/com/study/kafka/config/KafkaConfig.java`
- `NewTopic` 빈을 등록해서 토픽을 “있으면 그대로, 없으면 생성”하도록 한다.
- 로컬 단일 브로커 기준으로 `partitions=1`, `replicas=1`로 되어 있다.

## 3) Producer(발행자)
`src/main/java/com/study/kafka/web/MessageController.java`
- `POST /api/messages` 요청을 받으면 `KafkaTemplate`로 토픽에 value를 전송한다.
- 현재는 key 없이 value만 보내므로, 파티션이 여러 개가 되면 라우팅이 “균등 분배” 쪽으로 동작할 가능성이 높다.

## 4) Consumer(수신자)
`src/main/java/com/study/kafka/messaging/MessageListener.java`
- `@KafkaListener`가 토픽을 구독한다.
- 수신하면 `Consumed message: ...` 로그를 출력한다.

## 5) 테스트에서 Kafka를 어떻게 다루나?
`src/test/java/com/study/kafka/KafkaMessagingIntegrationTest.java`
- `@EmbeddedKafka`로 “테스트 전용 Kafka”를 띄운다(로컬 docker Kafka와 별개).
- `KafkaTemplate`로 보내고, 테스트용 `@KafkaListener`가 받았는지 `CountDownLatch`로 검증한다.

테스트에서 Embedded Kafka를 쓰는 이유:
- docker/kafka 의존 없이 테스트가 돌아가고
- 토픽/포트 충돌 같은 환경 이슈를 줄일 수 있다.

