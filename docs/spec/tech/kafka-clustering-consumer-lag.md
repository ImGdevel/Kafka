# 컨슈머 Lag 모니터링 문제

## 1. 언제 발생하는가

컨슈머 Lag은 항상 존재하며, 아래 상황에서 **문제가 될 만큼** 커진다.

| 상황 | 설명 |
|---|---|
| 컨슈머 처리 속도 < 프로듀서 발행 속도 | lag이 지속 증가 |
| 컨슈머 인스턴스 감소 | 남은 인스턴스가 더 많은 파티션 처리 → 처리 속도 저하 |
| 처리 로직에 외부 호출(DB, 외부 API) 있을 때 | 지연이 쌓이면서 lag 증가 |
| 브로커 장애 후 복구 시 | 중단된 기간만큼 lag 누적 |

### 클러스터에서 특수한 문제

단일 브로커에서는 전체 lag를 한 곳에서 볼 수 있었지만, **클러스터에서는 lag이 파티션별로 분산된다.**

```
파티션 0 → kafka-1 리더 (lag: 500)
파티션 1 → kafka-2 리더 (lag: 0)
파티션 2 → kafka-3 리더 (lag: 1200)
```

특정 브로커의 파티션만 lag이 쌓이는 경우, 단일 브로커만 보면 전체 상황을 파악할 수 없다.

---

## 2. 어떤 문제가 생기는가

**모니터링 부재 시:**

- lag이 쌓이는 것을 늦게 발견 → 알림 처리 지연 → 사용자 경험 저하
- 특정 파티션만 lag이 집중되어도 감지 불가 → 파티션 핫스팟 파악 불가
- 컨슈머가 DLT로 빠진 메시지 수를 추적 못함 → 실패율 파악 불가
- 장애 발생 시 어느 시점부터 처리가 밀렸는지 파악 불가

**lag 미탐지로 인한 실제 영향:**

```
notification.requested lag = 10,000 (처리 속도: 100/s)
→ 현재 들어오는 알림이 100초 후에 처리됨
→ 사용자는 알림을 100초 늦게 받음
→ 모니터링 없으면 인지 불가
```

---

## 3. 어떻게 해결하는가

### 즉시 사용 가능: kafka-consumer-groups.sh

```bash
# 전체 컨슈머 그룹 lag 확인
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group notification-worker-group

# 출력:
# GROUP                     TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
# notification-worker-group notification.requested  0          1000            1500            500  worker-xxx
# notification-worker-group notification.requested  1          800             800             0    worker-xxx
# notification-worker-group notification.requested  2          600             1800            1200 worker-xxx

# 모든 그룹 lag 한번에 확인
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --all-groups
```

**주요 컬럼:**

| 컬럼 | 의미 |
|---|---|
| `CURRENT-OFFSET` | 컨슈머가 커밋한 마지막 오프셋 |
| `LOG-END-OFFSET` | 파티션의 마지막 오프셋 (최신 메시지 위치) |
| `LAG` | `LOG-END-OFFSET - CURRENT-OFFSET` |
| `CONSUMER-ID` | 해당 파티션을 소비 중인 컨슈머 인스턴스 |

### 실무 모니터링 도구

| 도구 | 특징 | 사용 방법 |
|---|---|---|
| **Kafka UI** (Provectus) | Docker로 즉시 실행, 파티션별 lag 시각화 | `docker compose`에 추가 |
| **kafka-lag-exporter** | Prometheus 메트릭 노출 | Grafana 대시보드 연동 |
| **Confluent Control Center** | 상용, 엔터프라이즈 기능 | 라이선스 필요 |
| **Burrow** (LinkedIn) | lag 히스토리 추적 | 별도 서버 필요 |

### Kafka UI 추가 방법 (학습용)

```yaml
# docker-compose.yml에 추가
kafka-ui:
  image: provectuslabs/kafka-ui:latest
  ports:
    - "8090:8080"
  environment:
    KAFKA_CLUSTERS_0_NAME: local-cluster
    KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka-1:29092,kafka-2:29092,kafka-3:29092
  depends_on:
    kafka-1:
      condition: service_healthy
```

→ `http://localhost:8090`에서 파티션별 lag, 토픽 상태, 컨슈머 그룹 할당 등을 시각화

---

## 4. 현재 코드 현황

현재 모니터링 수단 없음. `kafka-consumer-groups.sh` 명령으로 수동 확인만 가능.

**파티션별 처리 속도 (현재 설계 기준):**

```
notification.requested 파티션 3개 × concurrency 3개
  → 파티션당 1개 스레드
  → notification-worker 처리 속도는 샌드박스 sender 응답 속도에 의존
  → SandboxEmailSender: 즉시 반환 (실패 시 backoff 1000ms)
  → lag 누적 가능성: 낮음 (샌드박스 환경)
```

---

## 5. 모니터링 기준 (참고)

| lag 수준 | 해석 | 대응 |
|---|---|---|
| 0 ~ 100 | 정상 | 없음 |
| 100 ~ 1,000 | 약간 밀림 | 처리 속도 추이 관찰 |
| 1,000 ~ 10,000 | 문제 징후 | concurrency 증가 또는 원인 분석 |
| 10,000+ | 심각 | 즉각 대응 (파티션 추가, 인스턴스 확장) |

> 임계값은 메시지 처리 시간과 서비스 SLA에 따라 다르다. 위 수치는 참고 기준이다.
