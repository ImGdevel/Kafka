# 🎯 Spring Kafka Study Lab

`Spring Boot + Kafka`를 빠르게 실험하고, 실수/복구/운영까지 한 번에 익힐 수 있는 학습 레포입니다.

이 프로젝트는 **메시지 발행/소비의 전 과정을 직접 실행**하고, 관련 개념을 단계적으로 정리한 위키와 함께 구성되어 있습니다.

## 🚀 한 줄 핵심

`Producer`와 `Consumer`의 동작을 직접 손으로 익히고  
`Ack / Offset / Partition / Connect / Streams / Monitoring`을 한 번에 이해하는 것이 목표입니다.

---

## 📚 문서 허브

- [🌐 GitHub Wiki 바로가기](https://github.com/ImGdevel/spring_kafka/wiki)
- [📂 로컬 Wiki 홈](./spring_kafka.wiki/Home.md)
- [📘 로컬 학습 노트 인덱스](./spring_kafka.wiki/Study/README.md)
- [📌 커밋/실험 실패 로그 정리](./spring_kafka.wiki/Solved/kafka-docker-compose.md)

---

## ⚡ 2분 안에 실행해 보는 데모

```bash
# 1) Kafka 실행 (KRaft)
docker compose up -d

# 2) 앱 실행
./gradlew :kafka-study:bootRun

# 3) 메시지 발송
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"hello kafka", "key":"user-1"}'
```

로그 예시:

```text
Consumed message: hello kafka (key=user-1, partition=0, offset=...)
```

```bash
# 정리
docker compose down -v
```

---

## 🧭 바로 알 수 있는 구성

| 항목 | 값 |
|---|---|
| Kafka 브로커 | `localhost:9092` |
| 실습 토픽 | `study-topic` |
| 컨슈머 그룹 | `study-group` |
| 기본 모듈 | `kafka-study` |

---

## 🎓 권장 학습 흐름

1. `Study/ROADMAP.md`를 보고 현재 목표를 확인한다.  
2. `kafka-study` 앱을 직접 실행한다.  
3. 토픽/파티션/오프셋을 바꿔가며 로그 패턴을 관찰한다.  
4. 에러 케이스(DLT, 리밸런싱, Lag)로 복구 능력을 늘린다.

---

## 👀 프로젝트 장점

- 🧪 **실행 위주의 학습**: 코드만 보는 수준이 아닌, 바로 실행해 보는 구성  
- 🧱 **단계별 문서화**: 초급 → 심화 → 실전까지 한 폴더에서 정리  
- 🛠 **운영 감각 강화**: Consumer Lag, Connect, 트러블슈팅까지 커버  
- 🚀 **확장성**: Redis Streams 비교 모듈로 대안 기술까지 함께 체감

---

## 🔗 앞으로 할 일

- 모듈별 실습 명령 정리  
- Docker Compose 모니터링 스택 확장  
- 위키 문서 간 상호 링크 강화
