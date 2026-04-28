<img width="1672" height="610" alt="ChatGPT Image 2026년 4월 27일 오후 06_57_44" src="https://github.com/user-attachments/assets/8c4f2f12-c540-4979-b422-c384c5e1d9ff" />

# [Spring Kafka Study](https://github.com/ImGdevel/spring_kafka/wiki)

Spring Boot 기반으로 Kafka 메시징 흐름을 실습하고, 같은 인터페이스로 Redis Streams까지 비교하는 기록 저장소 입니다.




<br />

## 모듈 구성

- `infra-message-queue`: 메시지 발행 인터페이스와 Kafka/Redis 구현
- `kafka-study`: Kafka 학습용 API, listener, DLT, 트랜잭션 실습 코드
- `kafka-redis-compare`: Redis Streams 비교 실습 코드
- `kafka-notification`: Producer 앱과 Worker 앱을 분리한 알림 전송 E2E 실습

<br />

## 빠른 실행

```bash
# 1) 인프라 실행
docker compose up -d

# 2) Kafka 실습 앱 실행
./gradlew :kafka-study:bootRun

# 3) 메시지 발행
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"hello kafka","key":"user-1"}'
```

<br />

## kafka-notification E2E 실행

```bash
# 1) Kafka + notification 앱 실행
docker compose --profile notification up --build -d

# 2) 알림 요청 발행
curl -X POST http://localhost:8082/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "channel":"EMAIL",
    "recipient":"user@example.com",
    "subject":"welcome",
    "body":"hello",
    "templateCode":"WELCOME"
  }'

# 3) 워커 로그 확인
docker compose logs notification-worker-app --tail=100
```

실패 경로 확인:

```bash
curl -X POST http://localhost:8082/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "channel":"EMAIL",
    "recipient":"user@example.com",
    "subject":"retry",
    "body":"force failure",
    "templateCode":"FAIL_ALWAYS"
  }'
```

<br />

## 비교 실행

```bash
# Redis Streams 비교 앱
./gradlew :kafka-redis-compare:bootRun

curl -X POST http://localhost:8081/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"redis-test"}'
```

<br />

## 참고

- [docker-compose.yml](./docker-compose.yml)
- [docker-compose.notification.yml](./docker-compose.notification.yml)
- [kafka-study/src/main/resources/application.yaml](./kafka-study/src/main/resources/application.yaml)
- [kafka-redis-compare/src/main/resources/application.yaml](./kafka-redis-compare/src/main/resources/application.yaml)
- [docs/spec/tech/kafka-notification/README.md](./docs/spec/tech/kafka-notification/README.md)
