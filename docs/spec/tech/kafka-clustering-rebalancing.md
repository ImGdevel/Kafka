# 컨슈머 그룹 리밸런싱 문제

## 1. 언제 발생하는가

컨슈머 그룹에서 아래 이벤트가 발생할 때 리밸런싱이 트리거된다.

| 트리거 | 설명 |
|---|---|
| 컨슈머 인스턴스 추가 | 새 앱 기동, scale-out |
| 컨슈머 인스턴스 제거 | 앱 종료, scale-in |
| 앱 재시작 | 배포, 크래시 후 재기동 |
| `session.timeout.ms` 초과 | 컨슈머가 일정 시간 heartbeat 미전송 시 브로커가 사망으로 판단 |
| `max.poll.interval.ms` 초과 | `poll()` 호출 간격이 설정값 초과 (처리 지연) |

클러스터 환경에서는 **브로커 수가 늘어나면서 파티션이 더 많아지고**, 리밸런싱 영향이 더 넓어진다.

---

## 2. 어떤 문제가 생기는가

### Eager Rebalancing (기본 동작, RangeAssignor / RoundRobinAssignor)

```
리밸런싱 트리거
  → 그룹 내 모든 컨슈머가 보유 파티션을 전부 반납
  → Coordinator가 새 할당 계산
  → 모든 컨슈머가 새 파티션 할당받아 소비 재개
```

**문제:**

- **Stop-The-World**: 파티션 반납 ~ 재할당 완료 기간 동안 **그룹 전체 소비 중단**
- **RangeAssignor 추가 문제**: 파티션이 컨슈머 순서대로 연속 배정되어 **특정 컨슈머에 파티션이 쏠릴 수 있음**

```
예시 — RangeAssignor, 3 토픽 × 3 파티션, 컨슈머 2개:
  Consumer-1: topic-A[0,1], topic-B[0,1], topic-C[0,1]  (6개)
  Consumer-2: topic-A[2],   topic-B[2],   topic-C[2]    (3개)
  → 부하 불균형
```

### 현재 상황 (notification-worker-app)

- `partition-assignment-strategy` 미설정 → Spring Kafka 기본값: **`RangeAssignor`** (Eager)
- `concurrency=3`, 파티션=3 → 재시작 시 3개 파티션 전체 소비 중단

---

## 3. 어떻게 해결하는가

### CooperativeStickyAssignor

```
리밸런싱 트리거
  1라운드: 이동이 필요한 파티션만 선별하여 반납
  → 나머지 파티션은 계속 소비 (중단 없음)
  2라운드: 반납된 파티션을 새 컨슈머에게 배정
  → 전체 소비 중단 없이 점진적 재할당 완료
```

추가로 "Sticky" 특성으로 **이전 할당을 최대한 유지**한다.
→ 캐시, 로컬 상태 등을 재로드하는 비용을 최소화.

**RangeAssignor vs CooperativeStickyAssignor 비교:**

| 항목 | RangeAssignor (기본) | CooperativeStickyAssignor |
|---|---|---|
| 리밸런싱 방식 | Eager (전체 반납) | Cooperative (점진 이동) |
| 소비 중단 | Stop-The-World | 없음 (이동 파티션만 순간 중단) |
| 할당 안정성 | 낮음 | 높음 (Sticky) |
| 부하 균등성 | RangeAssignor는 불균형 가능 | 균등 배분 |
| 적합 환경 | 단순 단일 토픽 | 멀티 토픽, 클러스터, 운영 환경 |

### 주의: 마이그레이션 시 혼재 금지

기존 컨슈머(RangeAssignor)와 새 컨슈머(CooperativeStickyAssignor)가 **동일 그룹**에 혼재하면 프로토콜 불일치로 무한 리밸런싱이 발생한다. 모든 인스턴스를 동시에 재시작해야 한다.

---

## 4. 현재 코드 적용 결과

### notification-worker-app
**파일:** [application.yaml](../../../kafka-notification/notification-worker-app/src/main/resources/application.yaml)

```yaml
consumer:
  # 변경 전: 설정 없음 → RangeAssignor (Eager)
  # 변경 후
  partition-assignment-strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

### kafka-study (기존 유지)
**파일:** [application.yaml](../../../kafka-study/src/main/resources/application.yaml)

```yaml
consumer:
  partition-assignment-strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
  # 기존부터 설정되어 있음
```

---

## 5. 검증

```bash
# notification-worker 기동 후 파티션 할당 확인
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group notification-worker-group

# 출력 예시:
# GROUP                    TOPIC                  PARTITION  CURRENT-OFFSET  CONSUMER-ID
# notification-worker-group notification.requested 0          100             worker-1
# notification-worker-group notification.requested 1          80              worker-1
# notification-worker-group notification.requested 2          120             worker-1

# 리밸런싱 실험: 새 인스턴스 추가
# → CooperativeStickyAssignor는 기존 3개 파티션을 재배분하는 동안에도 소비 지속
```
