# 06. 이 프로젝트의 Spring Kafka 코드 읽기

이 프로젝트는 “HTTP로 메시지 받기 → Kafka로 발행 → 컨슈머가 로그로 출력” 흐름을 최소 코드로 구성했다.

## 1) 설정 파일
`kafka-study/src/main/resources/application.yaml`
- `spring.kafka.bootstrap-servers: localhost:9092`
- `spring.kafka.consumer.group-id: study-group`
- `app.kafka.topic: study-topic`

## 2) 토픽 생성(애플리케이션 시작 시)
`infra-mq/src/main/java/com/study/kafka/config/KafkaConfig.java`
- `NewTopic` 빈을 등록해서 토픽을 “있으면 그대로, 없으면 생성”하도록 한다.
- 로컬 단일 브로커 기준으로 `partitions=1`, `replicas=1`로 되어 있다.
- `app.mq.type=kafka`일 때만 빈이 활성화된다(기본값이기도 함).

## 3) Producer(발행자)
`kafka-study/src/main/java/com/study/kafka/web/MessageController.java`
- `POST /api/messages` 요청을 받으면 `MessagePublisher`(애플리케이션 인터페이스)로 발행을 위임한다.

`infra-mq/src/main/java/com/study/messaging/MessagePublisher.java`
- “메시지를 발행한다”는 유스케이스 인터페이스다(컨트롤러가 Infra에 직접 의존하지 않게 함). 다른 메시지 브로커 구현으로 교체할 때 이 인터페이스를 공통으로 쓴다.

`infra-mq/src/main/java/com/study/kafka/infra/kafka/KafkaMessagePublisher.java`
- `MessagePublisher`의 Kafka 구현체다.
- 내부에서 `KafkaTemplate`로 토픽에 전송한다.
- 요청에 `key`가 있으면 `send(topic, key, message)`로 보내고, 없으면 `send(topic, message)`로 보낸다.

## 4) Consumer(수신자)
`kafka-study/src/main/java/com/study/kafka/messaging/MessageListener.java`
- `@KafkaListener`가 토픽을 구독한다.
- 수신하면 `Consumed message: ... (key=..., partition=..., offset=...)` 형태로 로그를 출력한다.

## 5) 테스트에서 Kafka를 어떻게 다루나?
`kafka-study/src/test/java/com/study/kafka/KafkaMessagingIntegrationTest.java`
- `@EmbeddedKafka`로 “테스트 전용 Kafka”를 띄운다(로컬 docker Kafka와 별개).
- `KafkaTemplate`로 보내고, 테스트용 `@KafkaListener`가 받았는지 `CountDownLatch`로 검증한다.

테스트에서 Embedded Kafka를 쓰는 이유:
- docker/kafka 의존 없이 테스트가 돌아가고
- 토픽/포트 충돌 같은 환경 이슈를 줄일 수 있다.
