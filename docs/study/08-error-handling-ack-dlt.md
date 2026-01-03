# 08. 오프셋 커밋/재처리 + 에러 핸들링(DLT)

목표: “중복 vs 유실”을 이해하고, Spring Kafka의 `AckMode`와 **Dead Letter Topic(DLT)** 기본 패턴을 체험한다.

## 1) 오프셋 커밋과 전달 보장
- **At-most-once**: 커밋을 먼저 → 처리 전에 죽으면 **유실** 가능
- **At-least-once(기본)**: 처리 후 커밋 → 커밋 전에 죽으면 **중복** 가능
- **Exactly-once**: 프로듀서 멱등 + 트랜잭션 + 컨슈머 처리/커밋까지 포함한 설계 필요(아래 “멱등/트랜잭션” 문서에서 다룸 예정)

### Spring Kafka `AckMode`(리스너 컨테이너)
- 기본은 `BATCH`(poll한 레코드 배치 단위 커밋).  
  환경 설정으로 바꿀 수 있다: `spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE` 등.
- `MANUAL` 계열을 쓰면 리스너에서 `Acknowledgment.acknowledge()` 호출로 커밋 시점을 직접 제어한다.

## 2) 에러 처리와 재시도
- 리스너에서 예외가 던져지면 `CommonErrorHandler`가 재시도/스킵/DLT 여부를 결정한다.
- 기본 `DefaultErrorHandler` + `FixedBackOff`(예: 2회 재시도) + `DeadLetterPublishingRecoverer`를 많이 쓴다.

## 3) DLT(Dead Letter Topic) 패턴
- “여러 번 재시도해도 실패”한 레코드를 다른 토픽(DLT)에 보내서 손실을 막고, 나중에 재처리/분석한다.
- key/partition/offset 정보가 함께 전달되므로 원본 레코드를 추적할 수 있다.

## 4) 이 프로젝트에 추가한 것들
- 설정 파일: `kafka-study/src/main/resources/application.yaml`
  - `app.kafka.dlt-topic: study-topic-dlt`
- 토픽 생성: `kafka-infra/src/main/java/com/study/kafka/config/KafkaConfig.java` (메인 + DLT 토픽 생성, 파티션 3개)
- 에러 핸들러: `kafka-infra/src/main/java/com/study/kafka/config/KafkaErrorHandlerConfig.java`
  - `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` (재시도 1회, 그 후 DLT 전송)
- 실패 시뮬레이션: `kafka-study/src/main/java/com/study/kafka/messaging/MessageListener.java`
  - payload에 `"fail"`이 포함되면 예외를 던져서 DLT로 보내지도록 함
- DLT 소비 로그: `kafka-study/src/main/java/com/study/kafka/messaging/DltMessageListener.java`
  - DLT에 쌓인 레코드를 별도 로그로 확인

## 5) 로컬에서 시나리오 실행
1) Kafka + 앱 실행
```bash
docker compose up -d
./gradlew bootRun
```
2) 정상 메시지(성공 → 커밋)
```bash
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"ok"}'
```
  - 로그: `Consumed message: ok (key=null, partition=..., offset=...)`

3) 실패 메시지(DLT로 이동)
```bash
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"please fail"}'
```
  - 로그 흐름:
    - 리스너에서 예외 → `DefaultErrorHandler`가 재시도 1회
    - 재시도 후에도 실패 → DLT로 전송
    - DLT 리스너 로그: `Consumed DLT message: ... (origPartition=..., origOffset=...)`

> 참고: 재시도 횟수/백오프/스킵 조건은 `KafkaErrorHandlerConfig`에서 조정 가능하다.

## 6) 왜 이런 구조가 중요한가?
- “실패를 무시하고 커밋”하면 **유실**이지만 중복은 없다.
- “처리 후 커밋”하면 **중복**은 생길 수 있지만 유실을 막는다.
- 재시도 + DLT를 쓰면 “최소 1번 + 실패 기록 보존” 패턴을 구현할 수 있다.
- 운영에서는 DLT 모니터링/재처리(역컨슘 or ETL) 체계를 함께 두어야 한다.

## 7) 추가로 실험해볼 것
- `spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE`로 바꾼 뒤, 리스너에서 `acknowledge()` 호출/생략에 따라 중복/유실을 비교
- `FixedBackOff`를 바꿔서 재시도 횟수/지연 체감하기
- DLT에 쌓인 레코드를 별도 컨슈머 앱에서 읽어 “재처리” 플로우 만들어보기
