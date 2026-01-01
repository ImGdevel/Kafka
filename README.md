# Kafka Study Project

This project is a minimal Spring Boot setup for practicing Kafka producers and consumers.

## Study Notes
- See `docs/study/README.md`

## Quick Start

1) Start Kafka (KRaft mode)
```
docker compose up -d
```

2) Run the app
```
./gradlew bootRun
```

3) Produce a message
```
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"hello kafka"}'
```

4) Watch the application logs for:
```
Consumed message: hello kafka
```

## Manual Test (docker compose)

1) Start Kafka
```
docker compose up -d
```

2) Run the app
```
./gradlew bootRun
```

3) Send a message
```
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"manual test"}'
```

4) Check logs for:
```
Consumed message: manual test
```

5) Stop Kafka when done
```
docker compose down -v
```

## Configuration
- Kafka bootstrap server: `localhost:9092`
- Topic: `study-topic`
- Consumer group: `study-group`
