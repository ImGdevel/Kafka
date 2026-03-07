# Spring Kafka Study

Spring Boot 기반으로 Kafka 메시징 흐름을 실습하고, 같은 인터페이스로 Redis Streams까지 비교하는 기록 저장소 입니다.

<br />

## Wiki

학습/운영 메모는 Wiki에 정리한다.

- [GitHub Wiki](https://github.com/ImGdevel/spring_kafka/wiki)
- [로컬 Wiki Home](./spring_kafka.wiki/Home.md)
- [로컬 Wiki README](./spring_kafka.wiki/README.md)
- [로컬 Wiki ROADMAP](./spring_kafka.wiki/ROADMAP.md)

<br />

## 모듈 구성

- `infra-message-queue`: 메시지 발행 인터페이스와 Kafka/Redis 구현
- `kafka-study`: Kafka 학습용 API, listener, DLT, 트랜잭션 실습 코드
- `kafka-redis-compare`: Redis Streams 비교 실습 코드

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
- [kafka-study/src/main/resources/application.yaml](./kafka-study/src/main/resources/application.yaml)
- [kafka-redis-compare/src/main/resources/application.yaml](./kafka-redis-compare/src/main/resources/application.yaml)
