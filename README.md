# Kafka Study Project

This project is a minimal Spring Boot setup for practicing Kafka producers and consumers.

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

## Configuration
- Kafka bootstrap server: `localhost:9092`
- Topic: `study-topic`
- Consumer group: `study-group`
